# Where `lua.wasm` differs from Valkey

Sections 1–6 come from wasm32's 32-bit types; sections 7–9 come from how `lua.wasm` is built and run (C stack size,
Chicory's memory limit, pointer size).

**Status:** sections 1–4 and 7 are fixed. `lua/Makefile` applies the patches in `lua/patches/` and two compile switches
(section 6), so every value derived from a Lua integer is 64-bit, as on Valkey, and links an 8 MiB C stack. The "Wasm"
columns in those sections show the build before the fix, for the record.

## What remains

These cannot be fixed on wasm32 with Chicory. Each is a deviation from Valkey; this is what a script or an embedder
observes.

| Deviation | What is observed | Section |
|---|---|---|
| Memory cap | Lua heap, C stack and static data share Chicory's 32767 pages (just under 2 GiB, less the 8 MiB stack). A script that needs more gets the ordinary Lua error `not enough memory`; `pcall` can catch it and the instance survives. Valkey has no Lua-specific limit. | 8 |
| Memory accounting and GC timing | Objects are smaller with 32-bit pointers, so `collectgarbage('count')` returns smaller figures, the incremental collector runs at different points, and weak tables lose entries at different times. | 9 |
| `tostring` address width | `tostring` of tables, functions and so on prints a 32-bit address (`table: 0x1e3f0`). Cosmetic. | 5 |
| Minimum thread stack | Evals must run on a JVM thread with at least 512 KiB of stack (every JVM default meets this). Below that, deep nesting overflows the JVM stack and the instance is poisoned. | 7 |
| Exception when something escapes `eval` | A failing `__tostring` on the result traps in Chicory (`WasiExitException`); JNI throws `IllegalStateException`. Both poison the instance. | — |

Valkey runs on 64-bit hosts (x86_64, arm64), where `ptrdiff_t`, `long`, `unsigned long`, `size_t` and pointers are
64-bit. `lua.wasm` is built for wasm32, where all of them are 32-bit. Valkey's bundled Lua 5.1 (`deps/lua`) and its
libraries use these types in a few places, so some scripts give different results on the Wasm backend than on Valkey.

The JNI backend (`JniValkeyLua`, native Valkey Lua through luajava) is a 64-bit build of the same sources, so it
behaves as Valkey does. It is the reference in the tables below.

## How these were found

1. Grepped `deps/lua/src` (Lua core, `lua_struct.c`, `lua_cmsgpack.c`, `lua_cjson.c`, `lua_bit.c`) for `long`,
   `unsigned long`/`strtoul`, `ptrdiff_t`, `lua_Integer`, `LUAI_MEM`, `size_t` and pointer casts.
2. Ran a probe script for each candidate on `WasmValkeyLua` and `JniValkeyLua` in the same JVM (`main` at `e50a879`)
   and compared the results.

Every row marked "probed" below was observed. The 4 GiB limit (section 5) comes from the types alone; in practice
Chicory caps memory lower, at just under 2 GiB (section 8, probed).

`lua_Number` is a 64-bit `double` on both, so plain arithmetic, `tostring` of numbers, cjson, cmsgpack values and the
formatting of `redis.call` arguments are identical. Only the integer conversions and C types below differ.

## 1. `lua_Integer` is `ptrdiff_t` (`luaconf.h:143`)

`luaL_checkint`, `luaL_checkinteger` and `lua_tointeger` convert a Lua number to `lua_Integer` (`ptrdiff_t`), and many
library functions then cast that to `int`.

- **Valkey:** the double fits in 64 bits, and the `int` cast keeps the low 32 bits, so 2^40 becomes 0 and 2^32 + 5
  becomes 5.
- **wasm32:** the double is converted straight to a 32-bit `ptrdiff_t`, and the conversion saturates, so any value of
  2^31 or more becomes 2147483647.

**Fixed** by the `luaconf.h` patch (`LUA_INTEGER long long`); tests `intArgumentsKeepLow32BitsOf64BitInteger` and
`largeIntegerArgumentsKeepLow32Bits`. The cmsgpack decode row is fixed by the `lua_cmsgpack.c` patch; test
`cmsgpackUint64AndInt64`.

All probed:

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
| `cmsgpack.unpack('\207' .. string.rep('\255', 8))` (uint64 `0xcf ff…ff`) | `-1` (wraps into the signed 64-bit `lua_Integer`) | `1.844674407371e+19` (cmsgpack's `BITS_32` path pushes a double) |

Some functions give the same result on both today, but only by accident. `string.sub`, `string.find`, `string.byte`
and `struct.unpack` with a position of 2^32 or more agree because the saturated wasm value also lands past the end of
the string. Widening `LUA_INTEGER` alone would truncate those positions instead (`string.sub('abcdef', 2^32 + 2,
2^32 + 3)` would return `"bc"`), so the `lstrlib.c` and `lua_struct.c` patches widen them too. Tests
`stringIndicesBeyond32BitsAreNotTruncated` and `structUnpackPositionIs64Bit`.

## 2. `string.format` integer conversions use `long` (`luaconf.h:746-747`)

Without `LUA_USELONGLONG`, `%d %i %x %X %o %u` format through `long` (`LUA_INTFRMLEN "l"`, `LUA_INTFRM_T long`). See
`lstrlib.c:782,787`.

**Fixed** by `-DLUA_USELONGLONG`; test `stringFormatIntegersAre64Bit`.

| Script | Valkey / JNI | Wasm (unpatched) |
|---|---|---|
| `string.format('%d\|%x\|%u', 2^40, 2^40, 2^32)` (probed) | `1099511627776\|10000000000\|4294967296` | `2147483647\|ffffffff\|4294967295` |

## 3. `struct` uses `long` and `size_t` (`lua_struct.c:49, 115-116`)

`STRUCT_INT` defaults to `long`. The `'l'`/`'L'` formats are `sizeof(long)` and `'T'` is `sizeof(size_t)`.

**Fixed** by `-DSTRUCT_INT='long long'` and the `lua_struct.c` patch; test `structLongAndSizeTAre64Bit`.

| Script | Valkey / JNI | Wasm (unpatched) |
|---|---|---|
| `struct.size('l')`, `'L'`, `'T'` (probed) | `8` | `4` |
| `struct.unpack('<i8', struct.pack('<i8', 2^40))` (probed) | `1099511627776` | `-1` |
| `struct.unpack('>l', struct.pack('>l', 2^40))` (probed) | `1099511627776` | `-1` |

This changes the bytes scripts produce, not only the values they read back. A script that packs with `'l'` and
stores the result writes 4-byte fields on wasm and 8-byte fields on Valkey.

## 4. `tonumber(s, base)` parses through `unsigned long` (`lbaselib.c:65-67`)

For a base other than 10, `luaB_tonumber` uses `strtoul`, which clamps to `ULONG_MAX`.

**Fixed** by the `lbaselib.c` patch (`strtoull`); test `tonumberWithBaseIs64Bit`.

| Script | Valkey / JNI | Wasm (unpatched) |
|---|---|---|
| `tonumber('ffffffffff', 16)` (probed) | `1099511627775` | `4294967295` |
| `tonumber('zzzzzzz', 36)` (probed) | `78364164095` | `4294967295` |

Hex literals in source (`0x7fffffffff`) and string-to-number coercion (`'0x10000000000' + 0`) are **not** affected.
`luaO_str2d` tries `strtod` first, and wasi-libc's `strtod` parses hex, so the `strtoul` fallback in `lobject.c`
never runs. Both give `549755813887` and `1099511627776` (probed).

## 5. `size_t` and pointers

- **Cosmetic (probed):** `tostring` of tables, functions and so on prints a 32-bit address, e.g. `table: 0x1e3f0`
  (14 characters) on wasm against 19 characters on JNI. Scripts should not depend on this.
- **Memory ceiling (from the types):** the whole Lua heap, every string length (`size_t`) and the garbage
  collector's byte counters (`lu_mem`/`l_mem`) fit in wasm32's 4 GiB address space. Chicory's own limit is lower and
  is reached first; see section 8. Because every allocation fits under it, a 32-bit `size_t` never truncates a real
  length.

## 6. The fix in the Wasm build

The rule: wherever Valkey's code holds a value that came from a Lua integer in `ptrdiff_t`, `long`, `unsigned long` or
`size_t`, the Wasm build holds it in a 64-bit type of the same signedness. On Valkey's hosts those types are 64-bit,
so this reproduces Valkey's arithmetic, including where Valkey then casts to `int`.

Compile switches (`CFLAGS` in `lua/Makefile`):

- `-DLUA_USELONGLONG`: an existing `luaconf.h` switch; `string.format` uses `%lld`/`long long`.
- `-DSTRUCT_INT='long long'`: `lua_struct.c` guards it with `#if !defined(STRUCT_INT)`.

Patches (`lua/patches/`, applied with `patch -p1 -F 0` after the sources are extracted; a patch that does not apply
exactly fails the build, and changing a patch or `VALKEY_SHA` re-extracts):

| Patch | Change | Why |
|---|---|---|
| `luaconf.h.patch` | `LUA_INTEGER` `ptrdiff_t` → `long long` | set unconditionally, so a `-D` cannot override it (section 1) |
| `lua_struct.c.patch` | `'l'`/`'L'`/`'T'` sizes → `sizeof(long long)`; `b_unpack`'s `pos` `size_t` → `unsigned long long` | section 3; `pos` would otherwise truncate a 64-bit position |
| `lbaselib.c.patch` | `strtoul` → `strtoull`, `unsigned long long n` | section 4 |
| `lstrlib.c.patch` | `posrelat` and the `str_sub`, `str_byte`, `str_find_aux` positions `ptrdiff_t` → `lua_Integer`; their `(size_t)` comparisons → `unsigned long long` | positions would otherwise truncate (section 1) |
| `lua_cmsgpack.c.patch` | `BITS_32` forced to 0 | it tests pointer width, which stays 32-bit; the 64-bit path uses `lua_Integer` (section 1) |

The sites were found by grepping `deps/lua/src` for integer types and conversions (`long`, `strtoul`, `ptrdiff_t`,
`lua_Integer`, `luaL_checkinteger`, `luaL_optinteger`, `lua_tointeger`, `size_t`) and for code that branches on
pointer or word width (`UINTPTR_MAX`, `UINT_MAX`, `SIZE_MAX`, `LONG_MAX`, `ULONG_MAX`, `sizeof(void *)`, `BITS_32`,
`LUAI_BITSINT`, `_LP64`). Checked and left alone: cmsgpack's offsets (`:858`, `:866`) are already `lua_Integer`; its
`LONG_MAX` abort and `UINT_MAX` asserts concern buffers of 2 GiB or more, which the memory cap rules out; `ldblib.c`
and `loslib.c` cast straight to `int`, as Valkey does; `LUAI_BITSINT` is the width of `int`, 32 on both.

### Only wasm64 fixes everything, and it is not available

The string-position cases, the address width and the 4 GiB ceiling all come from `ptrdiff_t`, `size_t` and pointer
width. Building for wasm64 (memory64) is the only change that removes every 32-bit type. Neither toolchain supports
it today (checked):

- **Chicory 1.7.5** (the latest release on Maven Central, March 2026) rejects memory64 modules. A one-function wasm64
  module built with wasi-sdk's clang (`--target=wasm64-unknown-unknown`) fails in `Parser.parse` with
  `MalformedException: integer too large, invalid memory limit: 4` (flag 4 marks a 64-bit memory). `MemoryLimits`
  holds page counts as `int`, capped at 65536 pages.
- **wasi-sdk 34** ships sysroots only for `wasm32-wasip1`, `wasm32-wasip1-threads`, `wasm32-wasip2` and
  `wasm32-wasip3`. There is no wasm64 libc, so Lua cannot be built for `wasm64-wasip1`.

So the Wasm backend stays wasm32. The patches above fix every observable integer difference; the remaining
`size_t`/pointer width shows only through the memory ceiling (section 8) and `tostring` addresses (section 5).

## 7. The C stack was 64 KiB, so deep nesting trapped instead of raising Valkey's error

**Fixed** by linking with an 8 MiB C stack (`-Wl,-z,stack-size=8388608` in `lua/Makefile`); tests
`nestingRaisesValkeysErrors` (shared) and `nestingErrorsAtMinimumThreadStack` (Wasm-only). The "Wasm" column below is
the 64 KiB build.

`lua.wasm` is linked with wasm-ld's default 64 KiB C stack (`__stack_pointer` starts at 65536). Valkey runs on a native
thread stack (megabytes), so its own nesting limits fire first: cjson's 1000-level limit and Lua's `LUAI_MAXCCALLS`
(200), which raises `C stack overflow`. On wasm the C stack runs out before those limits are reached.

All probed:

| Script | Valkey / JNI | Wasm (64 KiB stack) |
|---|---|---|
| `cjson.encode` of a table nested 800 deep | succeeds | succeeds |
| `cjson.encode` of a table nested 1000 deep | error `Cannot serialise, excessive nesting (1001)` | trap: `WasmRuntimeException: out of bounds memory access: attempted to access address: -376` |
| `cjson.decode(string.rep('[', 2000) .. string.rep(']', 2000))` | error `Found too many nested data structures (1001) at character 1001` | trap (address -48) |
| `string.gsub` with a callback that recurses through `string.gsub`, 195 levels | `'y'` | trap (address -224) |
| the same, 250 levels | error `C stack overflow` | trap (address -224) |

What a script saw:

- The trap is not a Lua error. `pcall` cannot catch it, and `eval` does not return `Result(ok=false)`.
- It escapes `eval` as a `WasmRuntimeException`, and the instance is poisoned: every later `eval` throws
  `IllegalStateException`.
- Any script can trigger it in a few lines.

The stack is placed at the bottom of linear memory, so an overflow runs below address 0 and traps. It does not
overwrite the heap.

These matched on both backends (probed): Lua-to-Lua recursion (15000 levels succeeds, 200000 raises `stack overflow`),
`pcall` recursing until `C stack overflow`, `cmsgpack.pack` and `cmsgpack.unpack` nested 2000–3000 deep, an
`__index` chain 190 deep, a `string.find` pattern nested 5000 deep, and the parser's nesting limit
(`loadstring` of 300 nested parentheses).

**The fix, and the JVM thread stack.** With 8 MiB, Valkey's own limits fire first and give the same errors as Valkey.
Chicory's compiler turns Wasm calls into JVM calls on the calling thread, so the deeper Wasm stack also uses more of
the JVM thread's stack. Measured with the four cases above:

| JVM thread stack | Platform thread | Virtual thread |
|---|---|---|
| 2 MiB (macOS arm64 default) | Valkey's errors, instance survives | same |
| 1 MiB (Linux x86_64 default) | Valkey's errors, instance survives | same |
| 512 KiB | Valkey's errors, instance survives | same |
| 256 KiB | `ChicoryException` escapes `eval`; instance poisoned | same |

So Wasm evals need a thread stack of at least 512 KiB, which every JVM default meets. The JNI backend needs more:
luajava runs Lua's C code on the thread's native stack, and at 512 KiB the 250-level `string.gsub` case overflows it
and the JVM dies (SIGILL). It passed at 768 KiB, 1 MiB and 2 MiB.

## 8. Chicory caps the Lua heap just under 2 GiB

Not fixable; pinned by `memoryCapIsAnOrdinaryLuaError` (Wasm-only, because JNI succeeds). It doubles a string by
concatenation until the cap is hit, which takes about a second; `string.rep` reaches the same error but builds its
result byte by byte, which took minutes for 1 GiB.

Chicory's `Memory.RUNTIME_MAX_PAGES` is 32767 pages of 64 KiB (backed by a single Java `byte[]`), so a `lua.wasm`
instance can never use more than 2 GiB − 64 KiB of memory. That covers the whole Lua heap, the 8 MiB C stack and the
static data, so Lua's data gets about 2 GiB − 8 MiB. Chicory grows the `byte[]` by copying it, so the JVM needs
heap for two copies near the cap (the test suite runs with `-Xmx4g`). `lua.wasm` starts with 2 pages and declares no maximum, so Chicory's cap is the only one. Valkey has no
Lua-specific limit, and `maxmemory` does not apply to Lua.

All probed:

| Script | Valkey / JNI | Wasm |
|---|---|---|
| `#string.rep('x', 2^28)` (256 MiB) | `268435456` | `268435456` |
| `#string.rep('x', 2^30)` (1 GiB) | `1073741824` | error `not enough memory` |
| four `string.rep('x', 2^29)` strings concatenated (2 GiB) | `2147483648` | error `not enough memory` |
| a table filled with 2^25 integers | `33554432` | `33554432` |

What a script sees:

- `not enough memory` is an ordinary Lua error: `pcall` can catch it, and `eval` returns `Result(ok=false)`. The
  instance survives.
- Building a string needs more memory than its final length, because `luaL_Buffer` grows its buffer and then copies
  it into the new string. A 1 GiB `string.rep` already fails, so the practical limit for a single string is somewhere
  between 256 MiB and 1 GiB.

This cannot be fixed in the build. A wasm32 module can address at most 4 GiB, and Chicory does not go past 2 GiB.

## 9. Memory accounting and GC timing

Lua's objects hold pointers, which are 4 bytes on wasm32 and 8 on Valkey's hosts, so the same data takes less memory.
Probed: 10000 empty tables add 647 KiB to `collectgarbage('count')` on wasm and 959 KiB on JNI.

What a script observes:

- the figures `collectgarbage('count')` returns;
- when the incremental collector runs, because Lua 5.1 drives it from those byte counts;
- therefore, when weak tables (`__mode`) lose their entries.

Not fixable on wasm32, and not tested.

## Tests

`ValkeyLuaTest` runs on both backends (`WasmValkeyLuaTest`, `JniValkeyLuaTest`) and pins 64-bit Valkey's values for
sections 1–4:

- `stringFormatIntegersAre64Bit`
- `structLongAndSizeTAre64Bit`
- `intArgumentsKeepLow32BitsOf64BitInteger`
- `stringIndicesBeyond32BitsAreNotTruncated`
- `largeIntegerArgumentsKeepLow32Bits`
- `tonumberWithBaseIs64Bit`
- `structUnpackPositionIs64Bit`
- `cmsgpackUint64AndInt64`

`stringIndicesBeyond32BitsAreNotTruncated` and `structUnpackPositionIs64Bit` passed on the unpatched build too; they
guard against a partial patch. Section 7: `nestingRaisesValkeysErrors` (shared) and
`nestingErrorsAtMinimumThreadStack` (Wasm-only, runs on a 512 KiB thread). Section 8:
`memoryCapIsAnOrdinaryLuaError` (Wasm-only). Sections 5 and 9 are documented, not tested.
