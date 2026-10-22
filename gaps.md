# Gaps between the backends and Valkey

The JNI backend (`JniValkeyLua`) is a 64-bit native build of Valkey's unpatched Lua sources, so it is the reference
for Valkey's behaviour. The Wasm backend (`lua.wasm` on Chicory) is a wasm32 build of the same sources: where Valkey's
hosts have 64-bit `ptrdiff_t`, `long`, `size_t` and pointers, wasm32 has 32-bit ones. `lua_Number` is a 64-bit
`double` on both, so arithmetic, `tostring` of numbers, cjson, cmsgpack values and `server.call` argument formatting
are identical.

Results marked "probed" were observed by running the same script on both backends in one JVM; the "Wasm" columns in
fixed sections show the build before the fix.

## Open gaps

| Gap | What a script sees | Backend | § |
|---|---|---|---|
| Memory cap | Lua data is capped just under 2 GiB: `not enough memory`, catchable. If building the reply runs out, `eval` throws and poisons the instance. Valkey has no Lua-specific limit. | Wasm | 5 |
| Memory kept after growth | Wasm memory never shrinks: an instance that grew keeps that `byte[]` (up to ~2 GiB of JVM heap) for its life. | Wasm | 5 |
| Unlimited recursion | Recursion Valkey does not limit (`cmsgpack.unpack` nesting, pattern backtracking, nested return values) is bounded by the JVM thread stack: Wasm poisons the instance, JNI kills the JVM. | both | 4 |
| String collation under a language locale | Wasm always collates in byte order, as Valkey's official images do. A server whose `locale-collate` or environment names a locale such as `en_US.UTF-8` orders strings differently (`'a' < 'B'`, `table.sort`). | Wasm | 6 |
| Out-of-range number returned | `return 2^63`, `1/0`, `-1/0`, `0/0` saturate on Wasm; Valkey matches on arm64 but gives `INT64_MIN` on x86-64. | Wasm on x86-64 | 6 |
| Memory accounting and GC timing | `collectgarbage('count')` is smaller, so the collector runs and weak tables clear at different points. | Wasm | 2 |
| `tostring` address width | `table: 0x1e3f0`, not a 64-bit address. Cosmetic. | Wasm | 2 |
| `string.dump` output | Different bytes and length (98, not 106, for `function() return 'abc' end`). | Wasm | 2 |
| `pairs` order over table- or function-keyed tables | Those keys hash by address, so the order differs. | Wasm | 2 |

Addresses, `pairs` order over such keys and GC timing depend on the allocator, so two Valkey servers already differ
on them; no backend can match them. The ones a script can actually rely on and hit are the memory cap and recursion depth;
collation only on a server configured with a language locale.

## Fixed

| Gap | Fix | Tests | § |
|---|---|---|---|
| Lua integers truncated to 32 bits | `luaconf.h`, `lstrlib.c` and `lua_cmsgpack.c` patches | `intArgumentsKeepLow32BitsOf64BitInteger`, `largeIntegerArgumentsKeepLow32Bits`, `stringIndicesBeyond32BitsAreNotTruncated`, `cmsgpackUint64AndInt64` | 1 |
| `string.format` integers through `long` | `-DLUA_USELONGLONG` | `stringFormatIntegersAre64Bit` | 1 |
| `struct` sizes and values through `long`/`size_t` | `-DSTRUCT_INT='long long'`, `lua_struct.c` patch | `structLongAndSizeTAre64Bit`, `structUnpackPositionIs64Bit` | 1 |
| `tonumber(s, base)` through `unsigned long` | `lbaselib.c` patch | `tonumberWithBaseIs64Bit` | 1 |
| `collectgarbage('step', n)` wraps | `lapi.c` patch | `collectgarbageStepSizeIs64Bit` | 3 |
| 64 KiB C stack trapped before Valkey's nesting limits | 8 MiB C stack | `nestingRaisesValkeysErrors`, `nestingErrorsAtMinimumThreadStack` | 4 |

`stringIndicesBeyond32BitsAreNotTruncated` and `structUnpackPositionIs64Bit` also passed before the fix; they guard
against a partial patch.

## 1. Integer widths

Found by grepping `deps/lua/src` (core, `lua_struct.c`, `lua_cmsgpack.c`, `lua_cjson.c`, `lua_bit.c`) for `long`,
`strtoul`, `ptrdiff_t`, `lua_Integer`, `LUAI_MEM`, `size_t` and pointer casts, then probing each candidate. All rows
probed.

**`lua_Integer` is `ptrdiff_t`** (`luaconf.h:143`). `luaL_checkint`, `luaL_checkinteger` and `lua_tointeger` convert
to it, and many functions then cast to `int`. On Valkey the `int` cast keeps the low 32 bits (2^32 + 5 becomes 5); on
wasm32 the conversion saturates at 2147483647.

| Script | Valkey / JNI | Wasm (unpatched) |
|---|---|---|
| `math.random(2^40)` | error `bad argument #1 to 'random' (interval is empty)` | a random number |
| `math.randomseed(2^32 + 5)` | same sequence as `math.randomseed(5)` | a different sequence (seed 2147483647) |
| `select('#', unpack({}, 1, 2^31))` | `0` | error `too many results to unpack` |
| `pcall(string.rep, 'x', 2^32 + 3)` | succeeds (`'xxx'`) | fails |
| `string.char(65 + 2^32)` | `'A'` | error `bad argument #1 to 'char' (invalid value)` |
| `table.insert(t, 2^32 + 1, 'x')` on an empty `t` | `t[1] == 'x'` | `t[1] == nil` |
| `select(2^32 + 2, 'a', 'b')` | `'b'` | `nil` |
| `cmsgpack.unpack_one(cmsgpack.pack(1, 2), 2^32 + 1)` | error `Start offset 1 greater than input length 2.` | error `Start offset 2147483647 greater than input length 2.` |
| `cmsgpack.unpack('\207' .. string.rep('\255', 8))` (uint64 max) | `-1` (wraps into `lua_Integer`) | `1.844674407371e+19` (`BITS_32` path pushes a double) |

`string.sub`, `string.find`, `string.byte` and `struct.unpack` with positions of 2^32 or more agreed before the fix
only because the saturated value also lands past the end of the string. Widening `LUA_INTEGER` alone would truncate
them instead (`string.sub('abcdef', 2^32 + 2, 2^32 + 3)` would return `"bc"`), so their positions are widened too.

**`string.format` uses `long`** for `%d %i %x %X %o %u` (`luaconf.h:746-747`, `lstrlib.c:782,787`).

| Script | Valkey / JNI | Wasm (unpatched) |
|---|---|---|
| `string.format('%d\|%x\|%u', 2^40, 2^40, 2^32)` | `1099511627776\|10000000000\|4294967296` | `2147483647\|ffffffff\|4294967295` |

**`struct` uses `long` and `size_t`** (`lua_struct.c:49, 115-116`): `'l'`/`'L'` are `sizeof(long)`, `'T'` is
`sizeof(size_t)`. This changes the bytes a script packs and stores, not only what it reads back.

| Script | Valkey / JNI | Wasm (unpatched) |
|---|---|---|
| `struct.size('l')`, `'L'`, `'T'` | `8` | `4` |
| `struct.unpack('<i8', struct.pack('<i8', 2^40))` | `1099511627776` | `-1` |
| `struct.unpack('>l', struct.pack('>l', 2^40))` | `1099511627776` | `-1` |

**`tonumber(s, base)` uses `strtoul`** for bases other than 10 (`lbaselib.c:65-67`), which clamps to `ULONG_MAX`.

| Script | Valkey / JNI | Wasm (unpatched) |
|---|---|---|
| `tonumber('ffffffffff', 16)` | `1099511627775` | `4294967295` |
| `tonumber('zzzzzzz', 36)` | `78364164095` | `4294967295` |

Hex literals and string coercion (`'0x10000000000' + 0`) are not affected: `luaO_str2d` tries `strtod` first, which
parses hex in wasi-libc.

## 2. Pointer width (open)

These remain because wasm32 pointers and `size_t` stay 32-bit (section 3).

- **`tostring`** of tables, functions and so on prints a 32-bit address (`table: 0x1e3f0`, 14 characters against 19).
- **`string.dump`** records `sizeof(size_t)` (4, not 8) in its header and writes lengths at that width:
  `#string.dump(function() return 'abc' end)` is 98 on Wasm, 106 on JNI.
- **`pairs` order** over tables, functions and other objects as keys differs, since they hash by address.
- **Memory accounting:** objects are smaller, so 10000 empty tables add 647 KiB to `collectgarbage('count')` on Wasm
  and 959 KiB on JNI. Lua 5.1 drives the incremental collector from these counts, so it runs, and weak tables
  (`__mode`) lose entries, at different points. Not tested.
- **Address space:** 4 GiB in principle, but Chicory's 2 GiB cap is reached first (section 5), so a 32-bit `size_t`
  never truncates a real length.

All probed except the address space.

## 3. The Wasm build

The rule: wherever Valkey holds a value derived from a Lua integer in `ptrdiff_t`, `long`, `unsigned long` or
`size_t`, the Wasm build holds it in a 64-bit type of the same signedness, which reproduces Valkey's arithmetic
including its later casts to `int`.

Compile switches (`CFLAGS` in `lua/Makefile`): `-DLUA_USELONGLONG` (an existing `luaconf.h` switch) and
`-DSTRUCT_INT='long long'` (guarded by `#if !defined(STRUCT_INT)`). Patches in `lua/patches/` are applied with
`patch -p1 -F 0`, so one that no longer applies exactly fails the build; changing a patch or `VALKEY_SHA`
re-extracts.

| Patch | Change | Why |
|---|---|---|
| `luaconf.h.patch` | `LUA_INTEGER` `ptrdiff_t` → `long long` | unconditional, so a `-D` cannot override it |
| `lua_struct.c.patch` | `'l'`/`'L'`/`'T'` → `sizeof(long long)`; `b_unpack`'s `pos` → `unsigned long long` | `struct` sizes; `pos` would truncate |
| `lbaselib.c.patch` | `strtoul` → `strtoull` | `tonumber(s, base)` |
| `lstrlib.c.patch` | `posrelat` and the `str_sub`, `str_byte`, `str_find_aux` positions → `lua_Integer`; their `(size_t)` comparisons → `unsigned long long` | positions would truncate |
| `lua_cmsgpack.c.patch` | `BITS_32` forced to 0 | it tests pointer width, which stays 32-bit |
| `lapi.c.patch` | `lua_gc`'s `LUA_GCSTEP` size → `unsigned long long` | `collectgarbage('step', n)` shifts `n` left by 10, wrapping at 2^22 |

Sites were found by grepping for the integer types and conversions above and for width tests (`UINTPTR_MAX`,
`UINT_MAX`, `SIZE_MAX`, `LONG_MAX`, `ULONG_MAX`, `sizeof(void *)`, `BITS_32`, `LUAI_BITSINT`, `_LP64`). Left alone:
cmsgpack's offsets (`:858`, `:866`) are already `lua_Integer`; its `LONG_MAX` and `UINT_MAX` checks concern buffers
the memory cap rules out; `ldblib.c` and `loslib.c` cast to `int` as Valkey does; `LUAI_BITSINT` is 32 on both.

**Only wasm64 would remove the 32-bit types, and it is not available:**

- Chicory 1.7.5 rejects memory64 modules (`MalformedException: integer too large, invalid memory limit: 4`; page
  counts are `int`), and its maintainers declined to add it
  ([#1277](https://github.com/dylibso/chicory/issues/1277), April 2026).
- wasi-sdk 34 ships no wasm64 sysroot, so Lua cannot be built for `wasm64-wasip1`.
- GraalWasm has experimental Memory64 since 23.0 ([changelog](https://github.com/oracle/graal/blob/master/wasm/CHANGELOG.md));
  not tried, and it would still need a wasm64 libc.

## 4. Stack depth

**Fixed: the C stack.** `lua.wasm` was linked with wasm-ld's default 64 KiB C stack, so it ran out before Valkey's own
limits (cjson's 1000 levels, `LUAI_MAXCCALLS` 200 raising `C stack overflow`). The overflow trapped: `pcall` could not
catch it, and `eval` threw `WasmRuntimeException` and poisoned the instance. Now linked with 8 MiB
(`-Wl,-z,stack-size=8388608`). All probed:

| Script | Valkey / JNI | Wasm (64 KiB stack) |
|---|---|---|
| `cjson.encode` of a table nested 1000 deep | error `Cannot serialise, excessive nesting (1001)` | trap |
| `cjson.decode(string.rep('[', 2000) .. string.rep(']', 2000))` | error `Found too many nested data structures (1001) at character 1001` | trap |
| `string.gsub` with a callback recursing through `string.gsub`, 195 levels | `'y'` | trap |
| the same, 250 levels | error `C stack overflow` | trap |

Lua-to-Lua recursion, `pcall` recursion, `cmsgpack.pack` nested 2000 deep, a 190-deep `__index` chain and the parser's
nesting limit matched even then.

**The JVM thread stack.** Chicory runs Wasm calls as JVM calls, so a deeper Wasm stack uses more JVM stack. With
8 MiB, Valkey's errors above fire first on platform and virtual threads at 512 KiB, 1 MiB (Linux x86_64 default) and
2 MiB (macOS arm64 default); at 256 KiB `ChicoryException` escapes and poisons the instance. Every JVM default is
enough.

**Open: recursion Valkey does not limit.** `cmsgpack.unpack` of nested arrays and `string.find`/`match`/`gsub`
backtracking recurse as deep as the input asks. On Valkey that uses the 8 MiB native stack; on Wasm it runs out of JVM
stack first and poisons the instance. Measured on macOS arm64 (deepest that worked / shallowest that poisoned):

| Script | 512 KiB | 1 MiB | 2 MiB | Valkey / JNI (8 MiB native) |
|---|---|---|---|---|
| `cmsgpack.unpack(string.rep('\145', N) .. '\192')` | 1000 / 2000 | 2000 / 3000 | 3000 / — | 3000 returns a table |
| `string.find(string.rep('a', N), string.rep('a?', N))` | 1000 / 1500 | 3000 / 5000 | 7000 / 10000 | 20000 works |

Converting a nested return value to its reply (`luaReplyToServerReply`, in `lua/valkey_reply.c`) also recurses per
level; Valkey stops at about 8000 with `-ERR reached lua stack limit`. For
`local t = {} for i = 1, N do t = {t} end return t`:

| Backend | 1 MiB | 2 MiB |
|---|---|---|
| Wasm | 3638 / 3656: `ChicoryException`, instance poisoned | 4270 / 4288: same |
| JNI | 6984 / 7000: JVM killed (SIGILL) | `reached lua stack limit` at 8000, as Valkey |

JNI is worse here: luajava runs Lua on the thread's native stack, so overflowing it kills the JVM. The 250-level
`string.gsub` case does so at 512 KiB, the `string.find` case at N = 20000 at the 2 MiB default. Running evals on a
dedicated large-stack thread would remove both; not done. Tests: `nestingErrorsAtMinimumThreadStack` (Wasm, 512 KiB
thread), `deepReplyNestingPoisons` (Wasm, 1 MiB thread).

## 5. Memory cap (open)

Chicory's `Memory.RUNTIME_MAX_PAGES` is 32767 pages of 64 KiB in one Java `byte[]`, so an instance has at most
2 GiB − 64 KiB for the Lua heap, the 8 MiB C stack and static data. `lua.wasm` declares no maximum, so this is the
only cap. Growing copies the `byte[]`, so the JVM needs room for two copies near the cap (the suite runs with
`-Xmx4g`). All probed:

| Script | Valkey / JNI | Wasm |
|---|---|---|
| `#string.rep('x', 2^28)` (256 MiB) | `268435456` | `268435456` |
| `#string.rep('x', 2^30)` (1 GiB) | `1073741824` | error `not enough memory` |
| four `string.rep('x', 2^29)` concatenated (2 GiB) | `2147483648` | error `not enough memory` |
| a table filled with 2^25 integers | `33554432` | `33554432` |

`not enough memory` is an ordinary Lua error: `pcall` catches it and the instance survives, keeping its grown memory.
`luaL_Buffer` copies on growth, so a single string tops out between 256 MiB and 1 GiB. Test:
`memoryCapIsAnOrdinaryLuaError`.

## 6. The eval reply and the C library

`eval` returns the RESP2 reply built by `lua/valkey_reply.c` on both backends, so the bytes match except for:

- **Out-of-range numbers** (probed). A returned number becomes `:` + `(long long)` of the double, which is undefined
  in C when out of range or NaN. arm64 and Wasm (`i64.trunc_sat_f64_s`) saturate, NaN giving 0; x86-64 gives
  `INT64_MIN`. So on x86-64, Valkey and JNI give `:-9223372036854775808` for `2^63`, `1/0`, `-1/0` and `0/0`, where
  Wasm gives `:9223372036854775807`, `:9223372036854775807`, `:-9223372036854775808` and `:0`. Tests:
  `numberCastSaturates` (Wasm), `numberCastFollowsHardware` (JNI).
- **String collation** (probed). `<` on strings and `table.sort` call `strcoll` (`lvm.c:211`). Valkey sets
  `LC_COLLATE` from `locale-collate` (default `""`, the environment; `server.c`, `config.c`). wasi-libc has only the C
  locale, so Wasm collates in byte order. So does Valkey in its official images: they set no `LANG`, so Debian-slim
  runs in the C locale, and musl's `strcoll` (Alpine) is `strcmp`. Wasm differs only from a server whose
  `locale-collate` or environment names a language locale such as `en_US.UTF-8`; matching that would need glibc's
  collation tables in `lua.wasm`, and Java's `Collator` orders differently. JNI is not the reference here: it follows
  the process locale through the host's C library, and macOS's `en_US.UTF-8` order is not glibc's. Not tested.
- **libm** (not probed). `math.sin`, `math.exp` and so on come from wasi-libc on Wasm, the host on JNI (Apple's on
  macOS) and glibc on Valkey on Linux; a last digit could differ under `string.format('%.17g', x)`.

## 7. Alternatives to the two backends

Judged against the gaps a script can hit: the memory cap and recursion depth (collation only under a language locale).

| Option | Fidelity | Fixes | Main risk |
|---|---|---|---|
| Wasm on Chicory (current) | Valkey's C, patched | — | memory cap; recursion depth |
| JNI through luajava (current) | the reference | — | native code in the JVM: a Lua crash or memory-safety bug takes down or corrupts it; a native build per platform |
| FFM instead of JNI | as JNI | none of the Wasm gaps | needs Java 22; the build targets 17 |
| wasm64 on GraalWasm | Valkey's C, fewer patches | pointer width, perhaps the cap | experimental; no wasm64 libc; heavy dependency |
| An existing Java Lua | another implementation, mostly another Lua version | — | we would own a fork |
| Port Valkey's Lua to Java | only as far as tests show | memory cap, `string.dump`, cast, accounting | size; every C behaviour missed is a new bug |
| Native Lua in a separate process | exact | everything, and isolates crashes | an IPC round trip per `server.call`; a process to ship and supervise |
| Chicory's build-time compiler | as Wasm | startup compile cost only | low |

**Existing Java Lua.** None is Lua 5.1 and maintained for general use. [LuaJ](https://github.com/luaj/luaj) is
effectively unmaintained. [Cobalt](https://github.com/cc-tweaked/Cobalt), a maintained LuaJ 5.1 fork, is built for
CC: Tweaked, warns against general use, promises no API stability and adds 5.2–5.4 backports.
[javalua](https://github.com/esrrhs/javalua) (5.4) leaves memory to the JVM's GC.
[luajvm](https://github.com/zhihulite/luajvm) (5.5.1) reports passing the official suite but has one commit and no
users. [taoguan/luaj](https://github.com/taoguan/luaj) and Luna are 5.3. javalua and luajvm do show a
function-by-function port of Lua is achievable.

**Porting Valkey's Lua to Java.** About 20k lines of C: Lua 5.1 with its standard library, cjson (strbuf, fpconv),
cmsgpack, struct, bit, `valkey_reply.c` and `valkey_globals.c`; `server.*` is already Java in `JniValkeyLua`.

- Fixes: the heap is bounded by `-Xmx` and returned when freed; `string.dump` and the cast can follow 64-bit Valkey;
  porting `lgc.c` rather than using the JVM's GC could make `collectgarbage('count')` match, since Lua counts requested
  sizes. No native build, no JVM crash from Lua, none of Lua's memory-safety bugs (for example CVE-2025-49844, a
  use-after-free in the parser).
- Does not fix: C recursion becomes Java recursion, bounded as on Wasm; Java's `Collator` is not `strcoll`; addresses
  and `pairs` order still differ.
- Adds: everything the C library supplies becomes ours to match: `printf` formats (`%.14g`, `%q`), `strtod` (hex,
  inf, nan), error text with chunk name and line, byte strings, coroutines, cjson's number formatting.
- Cost, estimated: one engineer for 3–6 months to pass Lua 5.1's suite and `ValkeyLuaTest`, then a long tail found by
  differential fuzzing against JNI. Proving it matches costs more than writing it. Performance is unknown until
  measured with JMH.

**FFM instead of JNI.** Drops luajava and the JNI symbol names in `native/valkey_jni.c`; bindings come from `jextract`,
and Lua can be built as plain C with `longjmp`, as Valkey builds it, instead of luajava's C++ exceptions. Still a
native build per platform, and the same rules: a Lua error must not unwind through Java frames, and a Java exception
escaping an upcall ends the JVM. Needs Java 22; supporting 17 too means keeping both.

**Native Lua in a separate process.** A small native host, or a stripped Valkey. The only option that is exact and
isolates crashes, at an IPC round trip per `server.call` and a process to ship and supervise.

**GraalVM's LLVM runtime (Sulong)** would run the C on the JVM; its maintenance status was not established.

**Conclusion.** If the memory cap and recursion depth do not matter for the scripts in question, Wasm is
already close and the remaining work is packaging. If memory safety without native code is the goal, only a Java port
delivers it: time-box a spike (Lua core plus `lstrlib`, gated on Lua 5.1's suite, `ValkeyLuaTest`, differential
fuzzing against JNI, and JMH) to measure the long tail first. Leave FFM until the minimum Java version is 22 or later.
