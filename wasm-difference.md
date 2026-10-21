# Where `lua.wasm` is 32-bit and Valkey is 64-bit

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

Every row marked "probed" below was observed. The 4 GiB limit (section 5) comes from the types alone and was not
probed, because exhausting memory hangs the run.

`lua_Number` is a 64-bit `double` on both, so plain arithmetic, `tostring` of numbers, cjson, cmsgpack values and the
formatting of `redis.call` arguments are identical. Only the integer conversions and C types below differ.

## 1. `lua_Integer` is `ptrdiff_t` (`luaconf.h:143`)

`luaL_checkint`, `luaL_checkinteger` and `lua_tointeger` convert a Lua number to `lua_Integer` (`ptrdiff_t`), and many
library functions then cast that to `int`.

- **Valkey:** the double fits in 64 bits, and the `int` cast keeps the low 32 bits, so 2^40 becomes 0 and 2^32 + 5
  becomes 5.
- **wasm32:** the double is converted straight to a 32-bit `ptrdiff_t`, and the conversion saturates, so any value of
  2^31 or more becomes 2147483647.

All probed:

| Script | Valkey / JNI | Wasm |
|---|---|---|
| `math.random(2^40)` | error `bad argument #1 to 'random' (interval is empty)` | a random number |
| `math.randomseed(2^32 + 5)` | same sequence as `math.randomseed(5)` | a different sequence (seed 2147483647) |
| `select('#', unpack({}, 1, 2^31))` | `0` | error `too many results to unpack` |
| `pcall(string.rep, 'x', 2^32 + 3)` | succeeds (`'xxx'`) | fails |
| `string.char(65 + 2^32)` | `'A'` | error `bad argument #1 to 'char' (invalid value)` |
| `table.insert(t, 2^32 + 1, 'x')` on an empty `t` | `t[1] == 'x'` | `t[1] == nil` |
| `select(2^32 + 2, 'a', 'b')` | `'b'` | `nil` |
| `cmsgpack.unpack_one(cmsgpack.pack(1, 2), 2^32 + 1)` | error `Start offset 1 greater than input length 2.` | error `Start offset 2147483647 greater than input length 2.` |

Some functions give the same result on both today, but only by accident. `string.sub`, `string.find`, `string.byte`
and `struct.unpack` with a position of 2^32 or more agree because the saturated wasm value also lands past the end of
the string. Section 6 explains why these become wrong on wasm after a partial fix.

## 2. `string.format` integer conversions use `long` (`luaconf.h:746-747`)

Without `LUA_USELONGLONG`, `%d %i %x %X %o %u` format through `long` (`LUA_INTFRMLEN "l"`, `LUA_INTFRM_T long`). See
`lstrlib.c:782,787`.

| Script | Valkey / JNI | Wasm |
|---|---|---|
| `string.format('%d\|%x\|%u', 2^40, 2^40, 2^32)` (probed) | `1099511627776\|10000000000\|4294967296` | `2147483647\|ffffffff\|4294967295` |

## 3. `struct` uses `long` and `size_t` (`lua_struct.c:49, 115-116`)

`STRUCT_INT` defaults to `long`. The `'l'`/`'L'` formats are `sizeof(long)` and `'T'` is `sizeof(size_t)`.

| Script | Valkey / JNI | Wasm |
|---|---|---|
| `struct.size('l')`, `'L'`, `'T'` (probed) | `8` | `4` |
| `struct.unpack('<i8', struct.pack('<i8', 2^40))` (probed) | `1099511627776` | `-1` |
| `struct.unpack('>l', struct.pack('>l', 2^40))` (probed) | `1099511627776` | `-1` |

This changes the bytes scripts produce, not only the values they read back. A script that packs with `'l'` and
stores the result writes 4-byte fields on wasm and 8-byte fields on Valkey.

## 4. `tonumber(s, base)` parses through `unsigned long` (`lbaselib.c:65-67`)

For a base other than 10, `luaB_tonumber` uses `strtoul`, which clamps to `ULONG_MAX`.

| Script | Valkey / JNI | Wasm |
|---|---|---|
| `tonumber('ffffffffff', 16)` (probed) | `1099511627775` | `4294967295` |
| `tonumber('zzzzzzz', 36)` (probed) | `78364164095` | `4294967295` |

Hex literals in source (`0x7fffffffff`) and string-to-number coercion (`'0x10000000000' + 0`) are **not** affected.
`luaO_str2d` tries `strtod` first, and wasi-libc's `strtod` parses hex, so the `strtoul` fallback in `lobject.c`
never runs. Both give `549755813887` and `1099511627776` (probed).

## 5. `size_t` and pointers

- **Cosmetic (probed):** `tostring` of tables, functions and so on prints a 32-bit address, e.g. `table: 0x1e3f0`
  (14 characters) on wasm against 19 characters on JNI. Scripts should not depend on this.
- **Memory ceiling (from the types, not probed):** the whole Lua heap, every string length (`size_t`) and the
  garbage collector's byte counters (`lu_mem`/`l_mem`) fit in wasm32's 4 GiB address space, minus everything else in
  the instance. Valkey has no such limit, and `maxmemory` does not apply to Lua. On wasm, a script that approaches
  the limit fails with an out-of-memory error where Valkey would carry on.

## 6. Fixing it in the Wasm build

### Confirmed

`lua.wasm` was rebuilt in a scratch copy with the following changes. That build passed `main`'s Wasm tests and matched
JNI for `string.format` (section 2), `struct` (section 3), `math.random`, `math.randomseed` and `unpack`.

- `-DLUA_USELONGLONG`: an existing `luaconf.h` switch that makes `string.format` use `%lld`/`long long`.
- `-DSTRUCT_INT='long long'`: `lua_struct.c` guards this with `#if !defined(STRUCT_INT)`.
- Patch `luaconf.h`: `#define LUA_INTEGER long long`. It is set unconditionally there, so a `-D` flag cannot
  override it.
- Patch `lua_struct.c` `optsize`: `'l'`/`'L'`/`'T'` return `sizeof(long long)`.

### What that build breaks

`string.sub`, `string.byte`, `string.find` and others keep their positions in `ptrdiff_t`, which is still 32-bit. A
64-bit `lua_Integer` is then truncated rather than saturated, so `string.sub('abcdef', 2^32 + 2, 2^32 + 3)` returns
`"bc"` instead of `""`. Unpatched wasm gets this right by accident (section 1).

### Not yet tried

- Section 4 needs its own patch: `strtoul` becomes `strtoull`, with `unsigned long long n`, in `lbaselib.c`.
- The newer rows of section 1 (`string.rep`, `string.char`, `table.insert`, `select`, `cmsgpack.unpack_one`) should
  follow a 64-bit `LUA_INTEGER`, but were not run against the patched build.

### Only wasm64 fixes everything

The string-position cases, the address width and the 4 GiB ceiling all come from `ptrdiff_t`, `size_t` and pointer
width. Building for wasm64 (memory64) is the only change that removes every 32-bit type. Whether Chicory supports
memory64 has not been checked.

## Tests

`ValkeyLuaTest` pins sections 1–3 against 64-bit Valkey's values; `JniValkeyLuaTest` runs them as is:

- `stringFormatIntegersAre64Bit`
- `structLongAndSizeTAre64Bit`
- `intArgumentsKeepLow32BitsOf64BitInteger`
- `stringIndicesBeyond32BitsAreNotTruncated`

`WasmValkeyLuaTest` overrides the first three to assert the current 32-bit results. A fix to the Wasm build should
delete those overrides, so the Valkey expectations apply to both backends. The last test passes on unpatched wasm by
accident and fails on the partially patched build, so it guards against that regression. Section 4 and the newer
rows of section 1 have no tests yet.
