# Memory analysis: Wasm vs JNI

Memory used by script execution on `WasmValkeyLua` (Chicory) and `JniValkeyLua` (luajava), measured 2026-10-22 at
`8ea002d`. Harness and raw data: `bench/`.

## Findings

1. **Idle instance cost.** A Wasm instance holds ~16 MiB of live Java heap (8.1 MiB of it the 130-page linear memory,
   mostly the 8 MiB C stack) and adds ~53 MiB of process footprint once G1 sizes the heap for it. The first instance
   also costs ~8 MiB Metaspace, ~4 MiB code cache and a ~200 MiB transient footprint peak while Chicory compiles
   `lua.wasm` and C2 compiles the result. An idle JNI instance holds nothing: each eval opens and closes its own
   `lua_State`.
2. **High-water memory is per Wasm instance and permanent.** One eval that builds a 16 MiB string leaves each Wasm
   instance with 1041 pages (65 MiB) of linear memory on the Java heap until the instance is discarded; 8 instances
   keep 592 MiB of live heap. JNI's high-water mark sits in malloc instead: ~170 MiB dirty after the same workload on
   macOS, shared by the process (8 instances run in turn: 213 MiB, not 8×).
3. **JNI churns direct buffers.** `JniValkeyLua.toBytes` uses luajava's `luaJ_tobuffer`, which allocates a
   `DirectByteBuffer` (plus `Cleaner` and `Deallocator`) per call: ~200 per eval of the `calls` workload. Cleaners are
   reachable from a static list until the Reference Handler runs them, so young GCs copy and promote them by the
   million. Over 20k such evals the heap after young GC grew 22 → 476 MiB in 5 s, pauses were 100–275 ms, and the
   process footprint peaked at 918 MiB (Wasm: 220 MiB). A full GC returns the heap to 1 MiB, so it is not a leak. A
   `tobytes` native in `native/valkey_jni.c` returning a `byte[]` (like `pushlstring`) would remove it. The script
   source buffer (`ByteBuffer.allocateDirect` per eval for `luaJ_loadbuffer`) is the same pattern, once per eval.
4. **Lua's own heap is smaller on Wasm.** wasm32's 32-bit pointers make `collectgarbage('count')` up to ~33% lower for
   table-heavy scripts; process-wide this is dwarfed by the per-instance runtime.

## Methodology

- Apple M2 Pro (12 cores, 32 GiB), macOS 27, Zulu JDK 25.0.4, G1, `-Xmx4g` (as surefire). `lua.wasm` and
  `liblua51arm64.dylib` built from this tree.
- Workloads (`bench/.../Scenarios.java`); the stub `redis.call` answers a 100-byte bulk string:
  `noop` (`return 1`), `calls` (100 `redis.call`s), `json` (cjson round trip of a 1000-element table), `reply`
  (10k-element array reply), `bigstr` (16 MiB `string.rep`, returns its length).
- **JMH** (`bench/jmh.sh`, `EvalAllocBench`): Java-heap bytes allocated per eval via `-prof gc`
  (`gc.alloc.rate.norm`). 3 forks, 5×2 s warmup, 5×2 s measurement, avgt, n=15; errors are JMH's 99.9% CI. Lua's
  own allocations live in linear memory or malloc on both backends, so this measures the Java-side overhead only.
- **Footprint probe** (`bench/footprint.sh`, `Footprint`): 14 configurations × 10 forks (140 fresh JVMs) in shuffled
  order. Each fork samples after two `System.gc()`s at baseline, after creating the instances, and after 20 evals per
  instance; values are deltas against the same process's baseline. Samples: live heap, Metaspace, code cache, NMT
  committed total, macOS `phys_footprint` and its peak (`footprint(1)`), dirty malloc, Wasm pages, and
  `collectgarbage('count')` at the end of the script. Reported as median [min..max] over the 10 forks.
- **Diagnostics:** NMT per-category summary (`bench/nmt.sh`), `-Xlog:gc` (`bench/gclog.sh`), class histogram
  including unreachable objects (`bench/histo.sh`), and 20k-eval runs (`bench/results/long.txt`).

## Results

Instance creation, MiB over baseline:

| | Live heap | Metaspace | Footprint | Footprint peak |
|---|---|---|---|---|
| Wasm ×1 | 18.65 | 8.0 | 74.9 [66.7..89.2] | 197 [190..212] |
| Wasm ×8 | 131.8 | 13.7 | 442.9 [424..479] | 455 [440..486] |
| JNI ×1 / ×8 | 1.03 | 3.25 | ~2.0 | ~2.2 |

NMT puts Wasm's extra native memory mainly in G1 heap committed (+69 MiB) and C2 compiler arenas (+26 MiB).

After 20 evals per instance (footprint MiB over baseline; Lua heap KiB at the end of the script):

| Workload | Wasm footprint | JNI footprint | Wasm Lua heap | JNI Lua heap |
|---|---|---|---|---|
| noop | 101.5 | 2.1 | 25.7 | 38.5 |
| calls | 102.0 | 3.3 | 26.7 | 39.5 |
| json | 116.2 | 2.7 | 315 | 438 |
| reply | 118.4 | 2.8 | 282 | 295 |
| bigstr | 159.9 (heap 77.4, 1041 pages) | 172.1 (malloc 170.5) | 53 969 | 53 742 |
| bigstr ×8 | 833.8 (heap 591.8) | 214.6 | | |

Java heap allocated per eval (JMH, B/op):

| Workload | Wasm | JNI |
|---|---|---|
| noop | 1,646 ± 83 | 2,536 ± 0 |
| calls | 44,422 ± 0 | 73,569 ± 0 |
| json | 2,526 ± 1 | 2,650 ± 19 |
| reply | 71,448 ± 1 | 71,483 ± 0 |
| bigstr | 4,183 ± 7 | 3,658 ± 9 |

Per-eval allocation is comparable except `calls` (finding 3); JMH recorded 5.7 s of GC time for JNI `calls` against
12 ms for Wasm.

## Caveats

- macOS only. glibc returns large blocks to the OS via mmap, so JNI's retained malloc after `bigstr` should be much
  smaller on Linux; Wasm's figures will not change, since linear memory never shrinks.
- Footprint and NMT mostly reflect G1 heap sizing under `-Xmx4g`, which is tunable; live heap is the
  configuration-independent number.
- `collectgarbage('count')` is read at the end of the script, garbage included, not at its peak.
- Timings in `bench/results/jmh.json` were taken with `-prof gc` on and are indicative only (Wasm 6–43× slower here).

## Reproducing

```sh
cd java && mvn install -DskipTests && cd ..
mvn -f bench/pom.xml package
bench/jmh.sh bench/results/jmh.json
bench/footprint.sh 10 20 bench/results/footprint.txt wasm:noop:1 jni:noop:1 wasm:calls:1 jni:calls:1 \
  wasm:json:1 jni:json:1 wasm:reply:1 jni:reply:1 wasm:bigstr:1 jni:bigstr:1 wasm:noop:8 jni:noop:8 \
  wasm:bigstr:8 jni:bigstr:8
python3 bench/summarize.py bench/results/footprint.txt
```

The footprint probe uses macOS's `footprint(1)`.
