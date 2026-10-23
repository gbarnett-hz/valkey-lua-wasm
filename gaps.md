# Gaps between the backends and Valkey

The JNI backend (`JniValkeyLua`) is a 64-bit native build of Valkey's unpatched Lua sources, so it is the reference
for Valkey's behaviour. The Wasm backend (`lua.wasm` on Chicory) is a wasm32 build of the same sources: where Valkey's
hosts have 64-bit `ptrdiff_t`, `long`, `size_t` and pointers, wasm32 has 32-bit ones. `lua_Number` is a 64-bit
`double` on both, so arithmetic, `tostring` of numbers, cjson, cmsgpack values and `server.call` argument formatting
are identical.

Results marked "probed" were observed by running the same script on both backends in one JVM; the "Wasm" columns in
fixed sections show the build before the fix. The suite runs on macOS arm64 and, through the `Dockerfile`, on Linux
x86_64 (emulated on Apple silicon).

## Open gaps

The first eight rows are ranked by how likely a script is to see the difference and what it costs when it does. None
of them blocks ordinary scripts; the first two decide whether an instance stays usable on pathological input, not
what a script computes. The rows after them were found by diffing against real servers (section 8) and are not ranked
against the first eight; the shebang and timeout rows do block the scripts they affect.

| Gap | What a script sees | Backend | Verdict | § |
|---|---|---|---|---|
| Unlimited recursion | Recursion Valkey does not limit (`cmsgpack.unpack` nesting, pattern backtracking, nested return values) is bounded by the JVM thread stack: the instance is poisoned. | Wasm | Blocker only if scripts unpack untrusted msgpack. Reaching it takes msgpack nested 1000–3000 deep, a pattern of thousands of items (backtracking nests per pattern item, not per input character) or a reply nested about 3600 deep. The embedder must rebuild a poisoned instance either way. | 4 |
| Memory cap | Lua data is capped just under 2 GiB: `not enough memory`, catchable. If building the reply runs out, `eval` throws and poisons the instance. Valkey has no Lua-specific limit. | Wasm | Blocker only for scripts that build hundreds of MiB in Lua. One string tops out between 256 MiB and 1 GiB, and Valkey's default `proto-max-bulk-len` (512 MB) keeps a value from `server.call` under that. Usually an ordinary, catchable error; only running out while building the reply poisons. | 5 |
| libm results | `math.sin`, `math.exp` and so on could differ in the last digit under `string.format('%.17g', x)`. Not probed. | Wasm | OK; `tostring`'s `%.14g` hides a last-bit difference. Probe against glibc on Linux (JNI on macOS uses Apple's libm, so it is not the reference). | 6 |
| String collation under a language locale | Wasm always collates in byte order, as Valkey's official images do. A server whose `locale-collate` or environment names a locale such as `en_US.UTF-8` orders strings differently (`'a' < 'B'`, `table.sort`). | Wasm | OK; differs only on a server configured with a language locale. | 6 |
| Memory accounting and GC timing | `collectgarbage('count')` is smaller, so the collector runs and weak tables clear at different points. | Wasm | OK; allocator-dependent, so already differs between Valkey builds. | 2 |
| `pairs` order over table- or function-keyed tables | Those keys hash by address, so the order differs. | Wasm | OK; address-dependent on Valkey too. String- and number-keyed order is unaffected. | 2 |
| `string.dump` output | Different bytes and length (98, not 106, for `function() return 'abc' end`). | Wasm | OK. | 2 |
| `tostring` address width | `table: 0x1e3f0`, not a 64-bit address. Cosmetic. | Wasm | OK; addresses differ between servers anyway. | 2 |
| No shebang | A script starting with `#!lua` (with or without `flags=`) fails to compile, so it does not run at all, and `no-writes` is never enforced. | Both | Blocker for any script with a shebang line. | 8 |
| No script timeout | `while true do end` never returns: no `-BUSY`, no `SCRIPT KILL`. Wasm: `eval(..., Duration timeout)` throws `ScriptTimeoutException` and resets the instance; interrupting the eval thread throws `ScriptInterruptedException` and poisons it. JNI cannot be stopped short of ending the process (the timed `eval` throws `UnsupportedOperationException`). | Both | Wasm: the embedder maps the timeout to a client reply. JNI: blocker if scripts can be untrusted or buggy. | 8 |
| Error text without the script SHA | Uncaught errors end `script: on @user_script:1.`, as at `VALKEY_SHA`; released Valkey and Redis send `script: <sha1>, on @user_script:1.` | Both | Decide which Valkey is the target; only clients parsing error text see it. | 8 |
| RESP3 | Every RESP3 reply type from the `Handler` raises `ERR unexpected reply from handler for server.call`, even after `setresp(3)` is accepted. | Both | Blocker only for scripts that call `setresp(3)`. | 8 |
| Script-context rules | Commands not allowed from scripts, script-local `SELECT`, `no-writes` and `acl_check_cmd`'s command check are left to the `Handler`; a pass-through to a server runs `MULTI`, `EVAL`, `SUBSCRIBE` and `CLIENT REPLY OFF` for real. | Both | The embedder's job; README "Divergences from Valkey". | 8 |
| `math.random` argument-error name | Always `bad argument #1 to 'random'`; Lua names the call site (`'?'` under `pcall`, `'r'` for `local r = math.random`). | JNI | OK; error text only. | 8 |
| Reply nesting at the Lua stack limit | One level deeper than Valkey before `reached lua stack limit` (32014 reply bytes against 32010 at 9000 levels). | JNI | OK. | 8 |
| NaN sign | `tostring(-(0/0))` is `-nan` on Wasm and Linux Valkey (glibc), `nan` on macOS Valkey and JNI. | Wasm | OK; matches Linux hosts. | 6 |

Addresses, `pairs` order over such keys and GC timing depend on the allocator, so two Valkey servers already differ
on them; no backend can match them.

## Fixed

| Gap | Fix | Tests | § |
|---|---|---|---|
| Lua integers truncated to 32 bits | `luaconf.h`, `lstrlib.c` and `lua_cmsgpack.c` patches | `intArgumentsKeepLow32BitsOf64BitInteger`, `largeIntegerArgumentsKeepLow32Bits`, `stringIndicesBeyond32BitsAreNotTruncated`, `cmsgpackUint64AndInt64` | 1 |
| `string.format` integers through `long` | `-DLUA_USELONGLONG` | `stringFormatIntegersAre64Bit` | 1 |
| `struct` sizes and values through `long`/`size_t` | `-DSTRUCT_INT='long long'`, `lua_struct.c` patch | `structLongAndSizeTAre64Bit`, `structUnpackPositionIs64Bit` | 1 |
| `tonumber(s, base)` through `unsigned long` | `lbaselib.c` patch | `tonumberWithBaseIs64Bit` | 1 |
| `collectgarbage('step', n)` wraps | `lapi.c` patch | `collectgarbageStepSizeIs64Bit` | 3 |
| 64 KiB C stack trapped before Valkey's nesting limits | 8 MiB C stack | `nestingRaisesValkeysErrors`, `nestingErrorsAtMinimumThreadStack` | 4 |
| Out-of-range or NaN numbers converted to integers: on x86-64, Wasm gave arm64's results (`string.format('%x', -1)` gave `0`, not `ffffffffffffffff`), and JNI's Java port of `server.set_repl`/`server.log` did too | Conversions go through `lua/valkey_cvt.c`, which `WasmValkeyLua` switches to x86-64's results on an x86-64 host; the Java port uses `ValkeyLua.d2i` | `numberConversionsFollowHardware` | 6 |
| Memory kept after growth: Wasm memory never shrinks, so an instance kept up to ~2 GiB of JVM heap for its life | Rebuild the instance after an eval leaves memory above `maxRetainedBytes` (default 128 MiB), carrying `math.random`'s state over | `memoryAboveMaxRetainedIsReleasedAndRandomContinues` | 5 |
| JNI killed the JVM on deep C recursion; on Linux before Valkey's own limits | evals run on pooled 8 MiB threads | `nestingRaisesValkeysErrors`, `nestingErrorsAtMinimumThreadStack`, `recursionValkeyDoesNotLimitUsesValkeySizedStack` | 4 |

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

On an 8 MiB thread (probed once, N = 9000), Wasm still throws `ChicoryException: call stack exhausted` and is
poisoned, where Valkey at `VALKEY_SHA` and JNI reply with `reached lua stack limit` in place of the innermost levels.

Tests: `nestingErrorsAtMinimumThreadStack` (both, 512 KiB caller thread), `deepReplyNestingPoisons` (Wasm, 1 MiB
thread).

**Fixed: JNI ran Lua on the caller's stack.** luajava runs Lua's C code on the native stack of the calling thread, and
overflowing it kills the JVM (SIGSEGV or SIGILL, no exception). On macOS arm64 that took recursion Valkey doesn't
limit: the `string.find` case above at N = 20000 at the 2 MiB default, or returning a table nested 7000 deep at 1 MiB.
On Linux it also beat Valkey's own limit, so `nestingRaisesValkeysErrors` killed the JVM on x86_64. Each nested
`string.gsub` callback holds a `luaL_Buffer`, whose size is `BUFSIZ`: 8192 bytes in glibc, 1024 on macOS. So on Linux
each level takes about 8 KiB more stack, whatever the CPU (Linux arm64 too). Probed on Linux x86_64, deepest nesting
of the `string.gsub` case that worked:

| Caller thread stack | 512 KiB | 1 MiB (Linux x86_64 default) | 2 MiB |
|---|---|---|---|
| Deepest that worked | 53 | 109 | 199 |

Valkey raises `C stack overflow` at 200, on a main thread with 8 MiB. So `JniValkeyLua.eval` now hands each eval to a
pooled thread with an 8 MiB stack (a cached pool of daemon threads; idle ones exit after a minute) and waits for it,
uninterruptibly, since the instance is not thread-safe. Exceptions are rethrown on the caller's thread. Valkey's
errors now fire first whatever the caller's stack, `string.find` at N = 20000 works and a table nested 10000 deep
replies with `reached lua stack limit`, as on Valkey.

What it costs, for choosing a backend:

- **The `Handler` runs on the pool thread**, not the caller's. Thread-locals the caller set (a transaction, a security
  context, logging MDC) are not visible to `server.call`; the embedder has to pass them in explicitly. Wasm runs the
  `Handler` on the caller's thread.
- **A thread handoff per eval**: two context switches. Not measured; measure it with JMH before relying on JNI for
  short, frequent scripts.
- **Native memory**: 8 MiB of stack reserved per eval running at once (committed only as it is touched), plus pool
  threads kept for a minute after use.
- **A caller interrupt does not stop a script**: the caller keeps waiting and its interrupt flag is restored afterwards.
  JNI could not stop a running script before either; script timeouts and `SCRIPT KILL` are not covered (README).

The alternative, requiring callers to run JNI evals on 8 MiB threads themselves, avoids the handoff but leaves a JVM
crash one misconfigured thread pool away; a virtual thread runs native code on its carrier's stack, which the
caller does not size.

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

`not enough memory` is an ordinary Lua error: `pcall` catches it and the instance survives.
`luaL_Buffer` copies on growth, so a single string tops out between 256 MiB and 1 GiB. Test:
`memoryCapIsAnOrdinaryLuaError`.

**Memory kept after growth (fixed).** Wasm memory never shrinks, and Chicory has no way to release it, so an instance
that grew kept that `byte[]` (up to ~2 GiB of JVM heap) for its life. After an eval leaves memory above
`maxRetainedBytes` (a constructor argument, default 128 MiB), `WasmValkeyLua` now builds a fresh instance from the
module it compiled once. Each eval already runs in a fresh `lua_State`, so the only state a script can observe in the
old instance is Valkey's `rand.c` PRNG, which lives as long as the instance on Valkey and JNI; `lua/valkey_rand.c`
exports it and the rebuild copies it over. A rebuild costs about 230 µs and allocates about 8.3 MiB (the initial memory,
mostly the C stack), measured with `RebuildBench` (JMH, Apple M2 Pro, JDK 25). Test:
`memoryAboveMaxRetainedIsReleasedAndRandomContinues`, which fails if the PRNG state is not carried.

## 6. The eval reply and the C library

`eval` returns the RESP2 reply built by `lua/valkey_reply.c` on both backends, so the bytes match except for:

- **Out-of-range numbers** (probed against Valkey 9.1.2 on arm64 and amd64, fixed). C's conversion of an
  out-of-range or NaN double to an integer is undefined, so Valkey gives a script its CPU's result, and 19 of 37 probes
  differed between arm64 and x86-64 Valkey. arm64 saturates (NaN gives 0); x86-64 gives `INT_MIN`/`LLONG_MIN`, and
  gcc's unsigned conversion wraps negatives. A script sees it in the reply (`return 1/0`: `:9223372036854775807` on
  arm64, `:-9223372036854775808` on x86-64), every integer argument (`lua_tointeger`: `string.sub('abcdef', 2^63)` is
  `''` on arm64, `'abcdef'` on x86-64; `select`, `string.char`, `math.ldexp`), `string.format` (`%x` of `-1` is `0` on
  arm64, `ffffffffffffffff` on x86-64), `cmsgpack.pack(2^63)`, `struct.pack`/`unpack`, `cjson`'s sparse-array check
  and `server.set_repl`/`server.log` with NaN. JNI's native code follows its CPU as Valkey does. Wasm's
  `trunc_sat` matched arm64 only, and so did JNI's Java port of `set_repl`/`log` (Java casts saturate). Now every
  conversion a script can observe in `lua.wasm` goes through `lua/valkey_cvt.c` (the patches route Lua's, `cjson`'s,
  `cmsgpack`'s and `struct`'s sites there; the others are followed by an exact-equality check or are in `os`
  functions Valkey removes), which `WasmValkeyLua` switches to x86-64's results on an x86-64 host, and the Java port
  converts with `ValkeyLua.d2i`. Both backends match the probes on Valkey on both CPUs. Test:
  `numberConversionsFollowHardware` (shared), one row per kind of site.
- **String collation** (probed). `<` on strings and `table.sort` call `strcoll` (`lvm.c:211`). Valkey sets
  `LC_COLLATE` from `locale-collate` (default `""`, the environment; `server.c`, `config.c`). wasi-libc has only the C
  locale, so Wasm collates in byte order. So does Valkey in its official images: they set no `LANG`, so Debian-slim
  runs in the C locale, and musl's `strcoll` (Alpine) is `strcmp`. Wasm differs only from a server whose
  `locale-collate` or environment names a language locale such as `en_US.UTF-8`; matching that would need glibc's
  collation tables in `lua.wasm`, and Java's `Collator` orders differently. JNI is not the reference here: it follows
  the process locale through the host's C library, and macOS's `en_US.UTF-8` order is not glibc's. Not tested.
- **NaN sign** (probed). `tostring` formats numbers with `%.14g`, so a NaN's sign comes from the C library:
  `tostring(-(0/0))` is `-nan` on Wasm (wasi-libc) and on Valkey 8.1.10 and 9.0.6 in their Linux images (glibc), and
  `nan` on Valkey and JNI on macOS. Wasm matches Linux hosts; JNI follows its host.
- **libm** (not probed). `math.sin`, `math.exp` and so on come from wasi-libc on Wasm, the host on JNI (Apple's on
  macOS) and glibc on Valkey on Linux; a last digit could differ under `string.format('%.17g', x)`.

## 7. Alternatives to the two backends

Judged against the gaps a script can hit: the memory cap and recursion depth (collation only under a language locale).

| Option | Fidelity | Fixes | Main risk |
|---|---|---|---|
| Wasm on Chicory (current) | Valkey's C, patched | — | memory cap; recursion depth |
| JNI through luajava (current) | the reference | — | native code in the JVM: a Lua crash or memory-safety bug takes down or corrupts it; a native build per platform; each eval runs on a pooled 8 MiB thread, so the `Handler` does not see the caller's thread-locals (§4) |
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

## 8. Probed against real servers

The sections above compare the backends with each other, taking JNI as Valkey. Here 278 probe scripts were run on
both backends and on real servers, and the RESP replies compared byte for byte. The servers were `valkey-server`
built at `VALKEY_SHA` (macOS arm64), the official Valkey 8.1.10 and 9.0.6 and Redis 7.2.16 images (Linux arm64, C
locale), and Redis 8.10.2 (macOS). The backends' `server.call` was forwarded byte for byte to a second `valkey-server`
at `VALKEY_SHA`, and evals ran on 8 MiB threads. The probes covered:

- return values and error replies;
- `server.call`/`pcall` replies and errors, and argument formatting;
- the globals allow list and read-only tables;
- every `server.*` function;
- `cjson`, `cmsgpack`, `struct`, `bit`, `string`, `math` and `table`;
- nesting limits, KEYS/ARGV, and state carried across evals.

The results were the same on the merged code after the x86-64 conversion and pooled-thread changes.

Replies that differ from `valkey-server` at `VALKEY_SHA`, out of 278: Wasm 30, JNI 22. Against Valkey 9.0.6, 92 (Wasm)
and 98 (JNI) out of 272 differ, 68 of them only by the script SHA in the error text.

**Both backends.**

- **No shebang.** Valkey strips a `#!lua` first line and enforces its flags. Here the line is Lua source, so every
  shebang script fails with `Error compiling script (new function): user_script:1: unexpected symbol near '#'`:
  - `#!lua` alone: Valkey runs the script (`:1`);
  - `flags=no-writes` with a write: `Write commands are not allowed from read-only scripts.`;
  - `flags=bogus`: `Unexpected flag in script shebang: bogus`;
  - `#!python`: `Could not find scripting engine 'python'`.
- **No script timeout.** Valkey's count hook runs every 100,000 instructions. Probed on Valkey 9.0.6 with `while true
  do end`:
  - after 6 s, `PING` from another client gets `-BUSY Valkey is busy running a script. You can only call SCRIPT KILL
    or SHUTDOWN NOSAVE.`;
  - `SCRIPT KILL` ends the script with `-ERR Script killed by user with SCRIPT KILL.`, also when the loop runs inside
    `pcall`.

  Here, after 10 s both backends are still running. `Thread.interrupt()` stops Wasm: `eval` throws
  `ScriptInterruptedException` (cause `ChicoryInterruptedException`) and poisons the instance (tests
  `interruptStopsInfiniteLoop`, `interruptStopsLongCCall`, `handlerInterruptingItsThreadStopsScript`). JNI is still running 5 s after the interrupt.

  Wasm's timed `eval` interrupts only while Lua runs, never during a handler call, and consumes its own interrupt
  (README "No script timeout"). Tests: `timeoutStopsInfiniteLoop`, `timeoutStopsLongCCall`,
  `timeoutDuringHandlerWaitsForItThenStopsBeforeMoreLua`, `timeoutRaceNeverLeavesOrSendsAnInterrupt` (10,000 evals
  around a 1 ms deadline), `otherInterruptDuringTimedEvalIsScriptInterrupted`, `timeoutWinsOverOtherInterruptWhichStaysSet`,
  `timedEvalThatFinishesLeavesFlagUnchanged`, `timedEvalWithFlagSetOnEntryIsInterruptedAsUntimed`,
  `suppliedWatchdogIsUsedAndNoThreadStarted`. Remaining: an outside interrupt landing between the watchdog's flag
  check and its interrupt is consumed with the library's; a Lua count hook instead of interrupts would close that
  for Lua code, but a long C call still needs the interrupt.
- **Error text.** At `VALKEY_SHA`, Lua is a module, and `script_lua.c` formats an uncaught error as
  `"%s script: on %s:%s."`. Valkey 8.1/9.0 and Redis include the script's SHA1:
  `script: 694a5fe1…, on @user_script:1.`. Valkey 8.1 also says `This Redis command is not allowed from script`, where
  `VALKEY_SHA` and 9.0 say `Valkey`.
- **RESP3.** A handler that accepts `setresp(3)` and then answers with `%`, `~`, `,`, `_`, `#`, `(` or `=` gets
  `ERR unexpected reply from handler for server.call` for each. On Valkey, `setresp(3)` followed by `HGETALL` returns
  the map, and `return false` replies `:0`.
- **Script-context rules.** With a pass-through handler:
  - `MULTI` returned `+OK` and queued every later command on the connection;
  - `EVAL` returned `:1`;
  - `SUBSCRIBE` subscribed;
  - `CLIENT REPLY OFF` left the connection waiting;
  - `ACL WHOAMI` returned `default`;
  - `SELECT 1` in one script changed the database the next script read.

  Valkey rejects the first five with `This Valkey command is not allowed from script` and keeps `SELECT` local to the
  script. `acl_check_cmd('nosuchcmd')` is `:1` from the default handler, and `-ERR Invalid command passed to
  server.acl_check_cmd()` on Valkey.

**JNI.**

- **`math.random` argument errors.** `JniValkeyLua` passes `"random"` to its `luaL_argerror` port. Valkey's
  `luaL_argerror` takes the name from the call site:
  - `pcall(math.random, 0)`: `bad argument #1 to '?' (interval is empty)` on Valkey and Wasm, `'random'` on JNI;
  - `local r = math.random return r(0)`: `'r'` on Valkey.
- **Reply nesting at the Lua stack limit.** For a table nested 9000 deep, JNI nests one level deeper than Valkey
  before `-ERR reached lua stack limit` (32014 bytes against 32010).

**Matched.** Everything else matched `valkey-server` at `VALKEY_SHA` on both backends:

- every return-value conversion;
- every error path and message, including `server.call`'s error rewrites;
- 18 number-argument formats;
- the globals allow list and its read-only protection;
- the libraries and the 64-bit integer cases;
- cjson, `string.gsub` and Lua recursion limits;
- binary-safe KEYS/ARGV;
- the PRNG, which carries over across evals on one instance as on Valkey (`math.randomseed(7)` in one eval, then
  `math.random(1000000)` in the next, gives 682036 everywhere).

String collation and `pairs` order differ only where sections 2 and 6 say. `collectgarbage('count')` deltas differ
between Valkey servers themselves, because their Lua state persists across evals.

**Redis is a different target.** Besides the SHA, Redis 7.2/8.10 have:

- no `server` global and no `__server__err__handler`, and Redis 7.2 has no `os`;
- `Redis` in the error rewrites (`Unknown Redis command called from script`) and `redis.log()` in argument errors,
  and Redis 7.2 says `Invalid debug level`;
- `Lua redis lib command arguments must be strings or integers`;
- `cjson.decode_array_with_array_mt` in Redis 8.10;
- a different `pairs` order over string keys.

Not probed: x86-64 servers, and `nestedErrorFailsWholeCall` (the probe used `EVAL` inside a script, which every
server rejects).
