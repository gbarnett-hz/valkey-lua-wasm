# Design: make `lua.wasm` match 64-bit Valkey

Date: 2026-10-21 · Status: draft for review

## Why

The Wasm backend (`WasmValkeyLua`, `lua.wasm` on Chicory) has to run Valkey/Redis scripts with the same semantics as
Valkey. Valkey runs on 64-bit hosts. `lua.wasm` is wasm32, and it is linked with a 64 KiB C stack. Both show through
to scripts: integers of 2^31 and above come out wrong, and deep nesting crashes the instance where Valkey raises an
error. `wasm-difference.md` records every case found, with the Valkey result and the Wasm result.

wasm64 would remove the 32-bit types, but Chicory 1.7.5 rejects memory64 modules and wasi-sdk 34 has no wasm64 libc
(`wasm-difference.md`, section 6). So the fix is to stay on wasm32 and patch Valkey's bundled Lua so that every value
derived from a Lua integer is 64-bit, as it is on Valkey.

The rule this design follows (stated by the project owner): **the Wasm backend must be faithful to Valkey's Lua
semantics. Where it cannot be, that is a deviation, and it must be documented together with what a script observes.**

Reference behaviour is Valkey on a 64-bit host. In this repo that is the JNI backend (`JniValkeyLua`): the same Valkey
sources, unpatched, built natively for 64 bits. Its results are the expected values.

## Goals

1. Every row in `wasm-difference.md` sections 1–4 gives Valkey's result on Wasm.
2. The nesting cases in section 7 give Valkey's errors on Wasm (`Cannot serialise, excessive nesting (1001)`,
   `Found too many nested data structures (1001) at character 1001`, `C stack overflow`), as ordinary Lua errors.
   No trap, no poisoned instance.
3. Nothing that matches today stops matching. In particular the two cases that match today only by accident:
   - `string.sub`/`string.byte`/`string.find` with positions of 2^32 and above;
   - `struct.unpack(fmt, data, pos)` with `pos` of 2^32 and above.
4. Every remaining difference is documented with its effect on scripts:
   - the memory cap (section 8);
   - the width of `tostring` addresses (section 5);
   - memory accounting and GC timing (below);
   - the minimum JVM thread stack (section 2 below).
5. Every row in the `wasm-difference.md` tables is a test, so the next change to the patches or the pinned Valkey
   revision is checked against all of them. This is how the anti-goal below is enforced.

## Non-goals

- wasm64.
- Changes to the JNI backend's code (it is the reference).
- Benchmarking.
- Integers of 2^63 and above. Converting such a double to a 64-bit integer is undefined in C, and Valkey itself gives
  different results on x86_64 and arm64.
- A configurable memory limit for the Wasm backend.

## Anti-goal

A build that passes the tests but still differs from Valkey somewhere nobody looked. A partial patch is the known way
to get there: widening `LUA_INTEGER` alone breaks the string and `struct` positions (goal 3). The patches go in as one
set, and every probed row stays under test.

## Constraints

- Valkey sources stay pinned at `VALKEY_SHA` in `lua/Makefile` (currently `7db2df1f…`). The JNI build
  (`native/Makefile`) uses the same SHA, unpatched.
- Toolchain stays wasi-sdk 34 and Chicory 1.7.5.
- The shared test suite (`ValkeyLuaTest`) runs against both backends. A test that cannot pass on Wasm is a documented
  deviation, not a shared test.

## What changes

### 1. Integer patches to Valkey's `deps/lua/src`

The principle: wherever Valkey's code holds a value that came from a Lua integer in `ptrdiff_t`, `long`,
`unsigned long` or `size_t`, the Wasm build holds it in a 64-bit type of the same signedness. On Valkey's hosts
those types are 64-bit, so this reproduces Valkey's arithmetic exactly, including where Valkey then casts to `int`
(e.g. `luaL_checkint` keeping the low 32 bits). Plain `int` stays `int`.

The known sites:

| File | Line(s) at the pinned SHA | Today | Change |
|---|---|---|---|
| `luaconf.h` | 143 | `#define LUA_INTEGER ptrdiff_t` | `long long` |
| `luaconf.h` | 739–747 | `string.format` integers via `long` unless `LUA_USELONGLONG` | build with `-DLUA_USELONGLONG` (existing switch, Makefile flag) |
| `lua_struct.c` | 48–49 | `STRUCT_INT` defaults to `long` | build with `-DSTRUCT_INT='long long'` (existing `#if !defined` guard, Makefile flag) |
| `lua_struct.c` | 115–116 | `'l'`/`'L'` are `sizeof(long)`, `'T'` is `sizeof(size_t)` | `sizeof(long long)` for all three |
| `lua_struct.c` | 298 (`b_unpack`) | `size_t pos` | 64-bit unsigned, as Valkey's `size_t` |
| `lbaselib.c` | 65–67 (`luaB_tonumber`) | `unsigned long n = strtoul(...)` | `unsigned long long`, `strtoull` |
| `lstrlib.c` | 36–49, 109–110, 499–501 | `posrelat` and the `str_sub`, `str_byte`, `str_find_aux` positions are `ptrdiff_t` | 64-bit signed |
| `lua_cmsgpack.c` | 27–38 | `BITS_32` is set from pointer width (`#if UINTPTR_MAX == UINT_MAX`), so it stays 1 on wasm32. Decode then pushes uint64 (`0xcf`, `:639`) as a double, and encode goes through `lua_Number` (`:358`). | force `BITS_32` to 0 (a `-D` can't override that `#if`) |

These are the sites found by two greps of `deps/lua/src`:
- for integer types and conversions: `long`, `strtoul`, `ptrdiff_t`, `lua_Integer`, `luaL_checkinteger`,
  `luaL_optinteger`, `lua_tointeger`, `size_t`;
- for code that branches on pointer or word width: `UINTPTR_MAX`, `UINT_MAX`, `SIZE_MAX`, `LONG_MAX`, `ULONG_MAX`,
  `sizeof(void *)`, `BITS_32`, `LUAI_BITSINT`, `_LP64`.

The second grep found the cmsgpack row. Already checked and needing no change:
- cmsgpack's offsets (`:858`, `:866`) are already `lua_Integer`.
- Its `LONG_MAX` abort (`:834`) and `UINT_MAX` asserts (`:555`, `:569`) concern buffer sizes of 2 GiB or more, which
  the memory cap rules out.
- `ldblib.c` and `loslib.c` cast straight to `int`, as Valkey does.
- `LUAI_BITSINT` is the width of `int`, which is 32 on both.

Valkey on a 64-bit host decodes cmsgpack uint64 `0xcf ff ff ff ff ff ff ff ff` as `-1`: it wraps into the signed
64-bit `lua_Integer`, then converts to a double. Today's wasm gives `1.844674407371e+19` (both probed). Encoding `2^40` already gives the same bytes on both (`cf0000010000000000`, probed). This is an
unsigned-to-signed integer conversion and behaves the same on x86_64 and arm64, so the 2^63 non-goal does not cover
it.

If the builder finds another site while doing this, it gets the same treatment and a test.

**How the patches are kept (my call).** One `.patch` file per source file under `lua/patches/`, in unified diff
format against the pinned Valkey tree. `lua/Makefile` applies them right after it extracts Valkey's sources, and the
build fails if any patch does not apply cleanly. That way each change is readable on its own, and a Valkey bump that
breaks a patch stops the build instead of silently producing an unpatched module. Changing a patch must trigger a
fresh extract and re-patch; a stale `.deps` must not survive. How to do that (stamp file, dependency on
`patches/*.patch`) is the builder's choice. Compile-time switches (`-DLUA_USELONGLONG`, `-DSTRUCT_INT`) go in
`CFLAGS`, not in patches.

### 2. C stack size

Link `lua.wasm` with an 8 MiB C stack (`-Wl,-z,stack-size=8388608`), about the size of a native thread's default
(my call). Keep the stack first in linear memory (today's layout, wasm-ld's default for wasi-sdk), so an overflow
still traps instead of overwriting the heap. With the larger stack, Valkey's own limits (cjson's 1000 levels, Lua's
`LUAI_MAXCCALLS` of 200) should fire first. This is a hypothesis until the section 7 tests pass. If 8 MiB is not
enough for any of them, raise it and record the size needed.

The stack comes out of the same ~2 GiB Chicory allows, so the memory cap for Lua data drops by 8 MiB. Document that
next to the cap.

**The JVM thread stack (checked).** Chicory's compiler turns Wasm calls into JVM calls on the calling thread, so a
deeper Wasm stack also uses more of the JVM stack. A scratch `lua.wasm` linked with the 8 MiB stack was run against
the four section 7 cases. With a JVM thread stack of 512 KiB, 1 MiB or 2 MiB, on platform threads and on virtual
threads, all four gave Valkey's results and the instance kept working:
- `Cannot serialise, excessive nesting (1001)`;
- `Found too many nested data structures (1001) at character 1001`;
- `'y'`;
- `C stack overflow`.

At 256 KiB the JVM stack ran out first. Chicory threw `ChicoryException`, which escapes `eval` and poisons the
instance. JVM defaults are 1 MiB on Linux x86_64 and 2 MiB on macOS arm64. Virtual threads behaved like platform
threads at the same `-Xss`.

So `WasmValkeyLua` needs no code change (my call). It does have a documented requirement: evals must run on a thread
with at least 512 KiB of stack, which every JVM default meets. Document it in the README's Wasm section and in
`wasm-difference.md`. Shared tests run at the JVM's default stack, with no `-Xss` in surefire.

### 3. Tests

In `java/src/test/java/valkeylua/`:

- **Delete the three overrides in `WasmValkeyLuaTest`** (`stringFormatIntegersAre64Bit`,
  `structLongAndSizeTAre64Bit`, `intArgumentsKeepLow32BitsOf64BitInteger`), so the Valkey expectations in
  `ValkeyLuaTest` apply to both backends. `WasmValkeyLuaTest` goes back to only constructing the backend, plus the
  one Wasm-only test below.
- **Add shared tests in `ValkeyLuaTest`** for every probed row of `wasm-difference.md` not already covered:
  - section 1: `math.randomseed(2^32 + 5)`, `string.rep`, `string.char`, `table.insert`, `select`,
    `cmsgpack.unpack_one` with 2^32-plus arguments;
  - section 4: `tonumber(s, base)` above 2^32, plus the hex-literal and coercion cases that already match;
  - `struct.unpack` with a position of 2^32 + 1 (goal 3);
  - section 7: the four nesting cases, asserting Valkey's error messages and that the same instance runs another
    `eval` afterwards;
  - cmsgpack in both directions:
    - decoding `0xcf` with all eight bytes `0xff` gives `-1`;
    - `cmsgpack.pack(2^40)` and a negative 64-bit value give Valkey's bytes (these match today; the tests guard the encode path the `BITS_32` change switches);
    - the result round-trips.

  Expected values are the JNI backend's, which are Valkey's. Group them next to the existing 64-bit tests, under the
  same comment.
- **Add a Wasm-only test in `WasmValkeyLuaTest`** that pins the memory cap: `string.rep('x', 2^30)` returns
  `Result(ok=false, "not enough memory")` and the instance survives. The JNI backend succeeds on it, which is why it
  can't be shared. It needs around 2 GiB of JVM heap for Chicory's memory. Set surefire's `argLine` so the test JVM
  has enough heap (e.g. `-Xmx4g`, my call), so the test sees the Lua error rather than a Java `OutOfMemoryError`.
- The `tostring` address width stays untested (cosmetic).

The full suite must pass on both backends: `make -C lua`, `make -C native`, `cd java && mvn test`.

### 4. Documentation

- **`wasm-difference.md`** becomes the record of what was different, how each case was fixed, and what remains.
  - Each section 1–4 and 7 row gets its fix and its test.
  - Section 6 ("Fixing it in the Wasm build") is replaced by the patch list as built.
  - "What remains" is the memory cap (section 8, now minus the 8 MiB stack) and the `tostring` address width
    (section 5), each with what a script observes.
- **`README.md`**:
  - "Where the backends differ" shrinks to the remaining deviations, with a pointer to `wasm-difference.md`. The
    "Rebuilding `lua.wasm` with the following…" passage goes away, because the build now does it.
  - The backend comparison table's "Integer width" row becomes "64-bit, as Valkey (patched)" for Wasm.
  - The Wasm backend section lists the patches and the stack size.
  - The build notes gain one line: after changing a patch or `VALKEY_SHA`, the Makefile re-extracts.
- The separate runtime-comparison doc ("WebAssembly runtimes for Java", on claude.ai) is out of scope.

### 5. Memory accounting and GC timing (documented, not fixed)

32-bit pointers make Lua's objects smaller on wasm. Measured: 10000 empty tables take 647 KiB by
`collectgarbage('count')` on wasm, against 959 KiB on JNI. A script can observe this in three ways:
- the numbers `collectgarbage('count')` returns;
- when the incremental collector runs, which is driven by those byte counts;
- therefore, when weak tables lose their entries.

This cannot be fixed on wasm32. It goes into `wasm-difference.md` and the README as a deviation, with that
description. The design does not budget an open-ended probing pass over other pointer-width effects (my call). The
pointer-width grep above is the bounded check. A script that depends on exact memory figures or GC timing is already
fragile across Valkey versions.

## Left to the builder

- The exact form of each patch and the stamp mechanism in `lua/Makefile`.
- The 64-bit type names in the patches (`long long`, `lua_Integer`, `int64_t`), as long as signedness matches
  Valkey's original type.
- Test names and grouping.
- Whether the section 7 nesting tests need a larger stack than 8 MiB (raise it if so and record why).
