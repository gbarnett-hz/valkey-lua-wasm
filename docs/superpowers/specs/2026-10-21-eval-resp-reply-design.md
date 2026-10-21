# Design: `eval` returns Valkey's RESP2 reply

Date: 2026-10-21 · Status: draft for review

## Why

`eval` returns `tostring` of the script's first return value, or of the error message. A real EVAL client gets
a RESP reply instead, built by Valkey from the Lua value. The difference shows to anyone who integrates this
library (Infinispan's RESP server writes the reply straight to a client socket):

- `return redis.call('SET','k','v')` yields `table: 0x…` where Valkey sends `+OK`.
- `return {1,'a'}` yields `table: 0x…` where Valkey sends `*2\r\n:1\r\n$1\r\na\r\n`.
- `return true`, `false` and `nil` yield `"true"`, `"false"`, `"nil"` where Valkey sends `:1`, `$-1`, `$-1`.
- `error('x')` yields `user_script:1: x` where Valkey sends `-ERR user_script:1: x script: on @user_script:1.`

The README lists both "script return value → RESP conversion" and "EVAL's server-side error formatting" under
"Not covered". This design closes both, so `eval` returns the exact bytes Valkey would send a RESP2 client.

Reference: Valkey at the pinned `VALKEY_SHA` (`7db2df1`):
- `src/modules/lua/script_lua.c`: `luaReplyToServerReply` (`:354`), `luaExtractErrorInformation` (`:1954`) and
  `luaCallFunction` (`:2043`, the error branch at `:2124`).
- `src/modules/lua/engine_lua.c`: `luaStateInstallErrorHandler` (`:73`), `initializeEvalLuaState` (`:113`) and the
  compile error (`:258`).
- `src/networking.c`: `addReplyErrorLength` (`:883`), `addReplyErrorFormatInternal` (`:1073`), `addReplyDouble`
  (`:1349`) and `addReplyNull` (`:1499`).
- `src/util.c`: `d2string` (`:818`) and `double2ll` (`:786`).

Port the C from there; don't re-derive behaviour. Note that the README's description of the error suffix
(`script: <sha>, on @user_script:1.`) is older Redis. This Valkey writes `script: on @user_script:1.` with no sha.

## Goals

1. **API.** `ValkeyLua.Result` becomes `Result(boolean ok, byte[] reply)`. `reply` is always a complete RESP2 reply,
   on success and on failure. `ok` is `false` when the script raised, failed to compile, or setup failed. A script
   that *returns* `{err=...}` gets `ok=true` with an error reply, as on Valkey, where the client sees the same bytes
   either way. Because the field is a `byte[]`, the record overrides `equals`/`hashCode` (content) and
   `toString` (the reply as text, with CR/LF escaped). The `value` field and the `tostring` path are removed.
2. **Success conversion.** This is `luaReplyToServerReply` for a RESP2 client. The script's RESP version
   (`server.setresp`) only matters for booleans:

   | Script returns | Reply |
   |---|---|
   | string | bulk `$<len>`, binary-safe |
   | number | `:` + `(long long)` C cast of the double (truncates) |
   | `true` | `:1` |
   | `false` | `$-1` at script RESP 2. At RESP 3 Valkey calls `ReplyWithBool`, which writes `:0` to a RESP2 client |
   | `nil`, function, userdata, thread | `$-1` |
   | table with string `err` | error reply, see goal 3 |
   | table with string `ok` | `+` + string, cut at the first NUL (`strncpy`), CR/LF mapped to spaces |
   | table with number `double` | bulk string of `d2string` (`nan`, `inf`, `-inf`, `-0`, `0`, integer form when `double2ll` is exact, else `fpconv_dtoa`) |
   | table with string `big_number` | bulk string, cut at the first NUL, CR/LF mapped to spaces |
   | table with `verbatim_string` = table of string `format` and string `string` | bulk string of `string`, binary-safe |
   | table with table `map` | array of `2n` elements, key then value, in `lua_next` order, each converted recursively |
   | table with table `set` | array of the keys, in `lua_next` order, converted recursively |
   | any other table | array of `t[1]`, `t[2]`, ... up to the first `nil`, converted recursively |
   | stack can't grow by 4 (`lua_checkstack`) | `-ERR reached lua stack limit` in place of that element |

   The fields are checked in this order: `err`, `ok`, `double`, `big_number`, `verbatim_string`, `map`, `set`, then
   array. The type dispatch reads fields with `rawget` and checks `lua_type == LUA_TSTRING`, so `return {err=42}`
   falls through to the array case. The error extraction that follows (goal 3) uses `lua_getfield`, so `__index`
   metamethods do run there.
3. **Error replies.** Valkey's error replies are built as follows:
   - The message is `-` + text. Trailing CR/LF is trimmed and inner CR/LF is mapped to spaces
     (`addReplyErrorFormatInternal`). The text is never given an extra `ERR`, because the format starts with `-`.
   - From a table (returned, or nested in an array): the text is `err` with C-string semantics (cut at the first
     NUL).
   - From a raised table (`luaCallFunction`): `luaExtractErrorInformation` reads `err`, `source` and `line` with
     `lua_getfield` + `lua_isstring`. Numbers count as strings, so `error({err=42})` gives
     `-42 script: on @user_script:1.`. If `err` is neither a string nor a number, the text is `ERR unknown error`.
     It is followed by ` script: on <source>:<line>.` when both `source` and `line` are set.
   - From a raised non-table: `ERR Error running script, ` + the first 100 bytes of the message (`%.100s`), or
     `execution failure` if the message isn't a string. A trailing `\n` is trimmed. This is reached by errors
     that bypass the error handler, such as `not enough memory`, and by `error in error handling`.
   - Compile error (`luaL_loadbuffer` fails): `-ERR Error compiling script (new function): ` + Lua's message.
     Valkey sends this through `addReplyErrorFormat(c, "%s", err)` (`eval.c:588`), so it gets the same trim, CR/LF
     mapping and NUL cut as above. The `ERR ` comes from `addReplyErrorLength`, because the text doesn't start with
     `-`. Compile messages can contain raw newlines (`near` a long-string token).
4. **Valkey's error handler.** Install `luaStateInstallErrorHandler`'s Lua chunk (verbatim, including
   `debug = nil`) and use it as the message handler of the script's `lua_pcall`. The handler:
   - wraps a non-table error as `{err='ERR '..tostring(err)}`;
   - adds `source` and `line` from the caller, or from one level up when the error came from a C function.

   The chunk must run before `luaLockGlobals`, because it captures `debug` and globals protection drops `debug`.
   Also set the globals `__server__err__handler` and `__redis__err__handler` to it, as `initializeEvalLuaState`
   does. They are on `valkey_globals.c`'s allow list but nothing sets them today.
5. **Setup failures specific to this port.** Valkey has no equivalent for these, so they get the plain error format
   with no suffix:
   - The per-eval `setResp(2)`/`setRepl(3)` reset returns an error. Its `{err=...}` becomes `-<err>`, the handler's
     own text.
   - A malformed KEYS/ARGV buffer gives `-ERR malformed KEYS/ARGV buffer`.

   Both give `ok=false`.
6. **Same code on both backends.** Following the precedent of `lua/valkey_globals.c`, the conversion, the error
   formatting and the error-handler install are shared C in a new `lua/valkey_reply.c`. It is built into
   `lua.wasm` and into the JNI natives, so the backends cannot drift. The Java port of `valkey_lua.c` doesn't grow a
   Java copy. The C entry points:
   - `void luaInstallErrorHandler(lua_State *L)`: run before the lock. It leaves the handler in the registry and in
     the two globals.
   - `int luaEvalReply(lua_State *L, int status, buffer *out)`: consumes the top of the stack, which is the return
     value when `status == 0`, or the error when the status is set. It appends the RESP2 reply. It needs to know
     the script's RESP version (goal 2's boolean row) and which failure kind occurred (compile, run or setup). How
     that is passed is left to the builder.

   JNI calls these through new natives in `native/valkey_jni.c`, like `lockGlobals`.

   The conversion can raise:
   - an `__index` metamethod runs during error extraction;
   - an allocation in `lua_pushstring` or `lua_pushvalue` fails with a memory error.

   Unprotected, this panics. On Valkey that crashes the server. On JNI luajava's panic handler kills the JVM, and on
   Wasm it exits and traps. So the shared C runs the conversion under `lua_cpcall` **on both backends**. A failed
   `cpcall` is an escape: `luaEvalReply` returns failure, and each backend throws `IllegalStateException`, which
   poisons the instance.
7. **The script's RESP version.** Valkey tracks it per run (`call_ctx.resp`, starting at 2). `luaSetResp` records the
   accepted version in the state for the conversion. Today it only forwards the version to the handler.
8. **Escape paths change.**
   - The `tostring` of the result goes away, so its failing-`__tostring` escape goes too. The handler only calls
     `tostring` on non-table errors, and scripts can't create userdata with a metatable (`newproxy` is off), so a
     failing `__tostring` should now be unreachable.
   - The new escape is a raising `__index` on an error table (goal 6). It poisons the instance with
     `IllegalStateException` on both backends.
   - A non-raising `__index` changes the reply bytes, as on Valkey.
     `error(setmetatable({}, {__index=function() return 'E' end}))` gives `-E script: on @user_script:1.`. The
     verbatim port handles this; nothing special is needed.

## Non-goals

- RESP3 output to the client (`%` maps, `#` booleans, `,` doubles, `_` null). The client side stays RESP2. RESP3
  *parsing* of handler replies also stays out.
- Error-stats flags (`ignore_error_stats_update` only affects Valkey's INFO counters, not the reply bytes).
- EVALSHA/sha handling, shebang flags, FUNCTION/FCALL, script timeouts and SCRIPT KILL, thread safety.

## Anti-goals

- Any reply byte that differs from what Valkey at the pinned SHA sends a RESP2 client, except the documented
  deviations below.
- A conversion path that can let a Lua error unwind through Java frames or reach luajava's panic handler.

## Constraints

- Fidelity rule (CLAUDE.md): JNI (64-bit native, Valkey's code) is the reference. Expected values come from running
  it. Wasm differences go in `wasm-difference.md` and the README's "Where the backends differ".
- **Number cast.** `(long long)` of an out-of-range or NaN double is undefined in C:
  - arm64 hardware saturates (NaN → 0);
  - x86-64 gives `INT64_MIN`;
  - wasm's `i64.trunc_f64_s` traps unless the build uses the saturating `trunc_sat` (`-mnontrapping-fptoint`).

  `lua.wasm` must not trap here. Build with saturating conversion (check whether wasi-sdk 34's default target
  already enables it), so Wasm matches JNI on arm64. Document that x86-64 Valkey and JNI-on-x86 give `INT64_MIN`
  for these inputs, and test `2^63`, `-2^63-1e5`, `1/0`, `-1/0` and `0/0`.
- **Deep nesting (decided by the user: port verbatim, document the deviation).** `luaReplyToServerReply` recurses
  once per nesting level. `reached lua stack limit` fires only near `LUAI_MAXCSTACK` (8000 slots, about 8000
  levels for nested arrays).
  - On JNI, Lua's C code runs on the JVM thread's native stack (1 MiB Linux x86-64, 2 MiB macOS by default).
  - On Wasm, Chicory recursion is bounded by the JVM thread stack (`wasm-difference.md` §7).

  So a deep return value such as `local t={} for i=1,8000 do t={t} end return t` can kill the JVM on JNI or poison
  the instance on Wasm before Valkey's limit fires. Keep the recursion. Document it beside the existing
  recursion notes: the README's JNI hazards and "Where the backends differ", and `wasm-difference.md`. Measure the
  depth at which each backend fails at the default stack size, and record the numbers. There is no shared test for
  `reached lua stack limit`. A Wasm-only test may pin the poisoning at a measured depth, in the way the memory-cap
  test does.
- **Stack-limit position.** If the limit is reached, which element gets the error depends on how many slots are in
  use when the conversion starts. The value passes into `lua_cpcall` differently from Valkey's main-stack call, so
  the position may differ from Valkey's by a level. The two backends must agree with each other, because the code
  is shared. Document the possible offset; don't contort the code to reproduce it.
- `native/` doesn't re-extract on source changes (CLAUDE.md). The new C file must be added to both Makefiles' source
  lists and dependency lines.

## Tests

- `ValkeyLuaTest` helpers change to compare RESP text:
  - `ok(script)` returns the reply as an ISO-8859-1 `String` after asserting `ok`;
  - `err(script)` does the same and asserts `!ok`;
  - small builders `bulk(s)`, `integer(n)`, `status(s)`, `error(s)`, `array(...)` and `NULL` construct expected
    values.

  Existing assertions are rewritten mechanically. For example, `assertEquals("hello 3", ok(...))` becomes
  `assertEquals(bulk("hello 3"), ok(...))`, and number results become `integer(...)`. Tests that only want a Lua
  value can keep `return tostring(...)` and wrap it in `bulk`.
- New shared tests cover every row of goal 2 and every case of goal 3. That includes nested error elements, NUL
  cuts, CR/LF mapping, `setresp(3)` + `return false` (needs a handler that accepts 3), the number-cast edge cases,
  a returned vs a raised `{err=}`, `return {err=42}` vs `error({err=42})`, `error({})`, `error(nil)`, a compile
  error (including one with a newline), a runtime error from inside `redis.call`, and `error()` called from a
  nested Lua function (for `line`). Also cover a non-raising `__index` on a raised error table (the bytes change)
  and a raising one (poisoned, `IllegalStateException`, on both backends).
- `WasmValkeyLuaTest`'s memory-cap test now expects `-ERR Error running script, not enough memory`.
- `readmeExample` and the README worked example move to `reply` (`$1\r\nv\r\n`).

## Docs

- README:
  - replace the `Result` description;
  - add a "Script reply" section: the goal 2 table, the error formats from goal 3, and the setup failures from
    goal 5;
  - drop both items from "Not covered";
  - fix the `<sha>` text;
  - reword the escape row in the backends table: the cause is now a raising `__index` on an error table, and both
    backends throw `IllegalStateException`. If that makes the backends identical, move it to "Not differences";
  - add the number-cast row if it differs on x86;
  - add the deep-nesting reply hazard with the measured depths.
- `wasm-difference.md`: reword the escape row the same way, and add the number-cast and deep-nesting notes.
- CLAUDE.md: mention `lua/valkey_reply.c` beside `valkey_globals.c`.

## Left to the builder

- The buffer type, and how `luaEvalReply` learns the failure kind and the RESP version.
- Where the error handler lives in the registry (Valkey uses `REGISTRY_ERROR_HANDLER_NAME`).
- The Wasm export shape for the reply (reusing `result_ptr`/`result_len` is fine).
- The exact test builders and how the existing assertions are split across commits.
