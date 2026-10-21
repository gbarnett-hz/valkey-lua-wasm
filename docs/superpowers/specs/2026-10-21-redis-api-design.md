# Design: the `server.*` / `redis.*` Lua API

Date: 2026-10-21 · Status: draft for review

## Why

The objective of this POC is to run the Lua scripts Redis/Valkey users already have, **unchanged**, against a
custom backend. Anything a real script touches that talks to the server is delegated to a Java handler the
integrator supplies (today's `redis.call` handler in `ValkeyLua` is the model). The Lua-facing contract must
match Valkey: same functions, same argument checks, same error strings, because scripts branch on them.

Today `lua/valkey_lua.c` exposes only `redis.call`/`redis.pcall`. Real scripts also need the rest of the
`server.*` table, the `KEYS`/`ARGV` globals, and a few global patches Valkey applies.

Reference: Valkey at the pinned `VALKEY_SHA` (`lua/Makefile`), `src/modules/lua/script_lua.c`
(`luaRegisterServerAPI`, `:1747`) and `src/modules/lua/engine_lua.c` (`initializeEvalLuaState`, `:114`).
Port the C from there; don't re-derive behaviour.

## Goals

1. A `server` global table with `redis` aliased to the **same** table (`lua_getglobal(server); lua_setglobal(redis)`).
2. In-module functions, ported from Valkey:
   - `sha1hex(s)`: Valkey's `src/sha1.c` compiled in.
   - `error_reply(s)`, `status_reply(s)`, including Valkey's quirk that on bad arguments they *return* an error
     table rather than raise.
   - `replicate_commands()`: always returns `true`.
   - `breakpoint()` returns `false`, `debug(...)` does nothing (Valkey's behaviour when no debugger session is active).
   - Constants: `REPL_NONE/AOF/SLAVE/REPLICA/ALL`, `LOG_DEBUG/VERBOSE/NOTICE/WARNING` (same numeric values as Valkey),
     `REDIS_VERSION`, `REDIS_VERSION_NUM`, `VALKEY_VERSION`, `VALKEY_VERSION_NUM`, `SERVER_NAME`.
3. Delegated functions: `call`, `pcall` (exist), `setresp`, `set_repl`, `log`, `acl_check_cmd`.
   Valkey's argument validation and messages run **in C first**; the handler only sees requests that passed them.
4. Global patches:
   - `pcall` replaced with Valkey's `luaRedisPcall` wrapper (converts `{err=}` tables to strings, pre-7 compatibility).
   - `math.random`/`math.randomseed` replaced with Valkey's `server_math_random` backed by `src/rand.c`
     (`serverLrand48`), so sequences match Valkey for the same seed.
5. `KEYS` and `ARGV`: `eval` takes keys and args and sets them as Lua arrays before running the script.
6. Bring existing `call`/`pcall` in line with Valkey (`luaArgsToServerArgv`, `script_lua.c:680`):
   - error messages `Please specify at least one argument for this call` and
     `Command arguments must be strings or integers` (current strings differ);
   - number → string: integer formatting when the double is an exact integer, else `fpconv_dtoa`
     (already compiled in). Current `%.17g` turns `0.1` into `0.10000000000000001`.
7. README documents, in full:
   - what runs inside the module vs. what is delegated (the two lists in goals 2–3), plus the global patches and
     `KEYS`/`ARGV`;
   - the `Handler` interface, with the Java integration (`ValkeyLua` + a test handler) as the worked example;
   - the complete reply contract below: success conversions per function, wrong-reply-type handling,
     replies not exceptions, nested errors, raise vs. return per function, the error-table format,
     the command error rewrites, exception conversion, and the per-eval reset;
   - deliberate divergences from Valkey (prefix-only rewrites, `setresp(3)` rejected, RESP2 only).
   Replace the current "Out of scope" line with the non-goals below.

## Non-goals (this piece of work)

Globals protection / read-only tables, removing `debug`, script timeouts and SCRIPT KILL, RESP3 reply parsing,
script return value → RESP conversion (eval still returns `tostring` of the result), FUNCTION/FCALL,
EVAL's server-side error formatting (`script: <sha>, on @user_script:1.`), thread safety.

## Anti-goals

- An error that differs from Valkey's in text or in raise-vs-return behaviour. That breaks unchanged scripts.
- A handler failure that escapes as a Java exception (see the reply contract).
- A handler reply of the wrong type that reaches the script as a plausible-looking value.

## Reply contract

Every delegated operation returns a RESP2 reply. The module turns it into what the script sees, as Valkey does.

### Success

`call`/`pcall` replies are converted recursively, as Valkey's reply callbacks do (`script_lua.c:880-1130`):

| Handler reply | Lua value |
|---|---|
| `+OK` (status) | `{ok="OK"}` |
| `:42` (integer) | number `42` |
| `$3\r\nbar\r\n` (bulk) | string `"bar"` |
| `$-1\r\n` (null bulk) | `false` |
| `*-1\r\n` (null array) | `false` |
| `*N ...` (array) | table `{1=..., N=...}`, elements converted recursively |
| `-CODE msg` as an array element | that element becomes an error table (see Errors) |

The other delegated operations:

| Function | Handler success reply | Script gets |
|---|---|---|
| `setresp`, `set_repl`, `log` | `+OK` | nothing (0 return values) |
| `acl_check_cmd` | `:1` / `:0` | `true` / `false` |

**Wrong reply type** (decided with the user): if a handler returns a reply outside the table above, e.g. `+OK` from
`aclCheckCmd` or `:5` from `setResp`, the module raises `{err="ERR unexpected reply from handler for <fn>"}`
(`<fn>` = the Lua function name, e.g. `server.acl_check_cmd`). This is a broken handler, treated like a thrown
exception, never a silently wrong value. An error reply is never "wrong type": it follows the Errors rules.
`call`/`pcall` accept any well-formed RESP2 reply.

### Errors

1. **Handlers never signal failure by throwing.** Every delegated operation returns a RESP2 reply; failure is an
   error reply (`-CODE message\r\n`).
2. **The C function decides raise vs. return**, as Valkey does:

   | Called from | Handler returns an error reply | Script sees |
   |---|---|---|
   | `call` | `-ERR ...` | Lua error raising `{err="ERR ..."}` |
   | `pcall` | `-ERR ...` | `{err="ERR ..."}` returned, no raise |
   | `setresp`, `set_repl`, `log`, `acl_check_cmd` | `-ERR ...` | Lua error raising `{err="ERR ..."}` |

3. **Error tables use Valkey's format** (`luaPushErrorBuff`, `script_lua.c:265`): leading `-` stripped, a bare
   message gets an `ERR ` prefix, trailing CRLF trimmed. Errors raised by the in-module checks use the same path,
   so `redis.setresp(4)` raises `{err="ERR RESP version must be 2 or 3."}`.
4. **Command error rewrites happen in C, on prefix alone** (decided with the user). Valkey's `luaProcessReplyError`
   (`script_lua.c:745`) rewrites four command errors before the script sees them, keyed on the server's `errno` plus a
   prefix. `errno` does not exist across the Wasm boundary, so the module matches the prefix only:

   | Handler's `call`/`pcall` reply | Script sees `err` |
   |---|---|
   | `-ERR unknown command '...` | `ERR Unknown command called from script` |
   | `-ERR wrong number of arguments for ...` | `ERR Wrong number of args calling command from script` |
   | `-ERR command ...` | `ERR This Valkey command is not allowed from script` |
   | `-NOPERM x` | `ERR ACL failure in script: x` |
   | any other `-CODE msg` | `CODE msg` unchanged |

   - Handlers should therefore return Valkey's own command error text; they get Valkey's script-visible errors for free.
   - Accepted divergence: an unrelated error that happens to start with one of these prefixes is also rewritten.
     Valkey would leave it alone because `errno` wouldn't match.
   - As in Valkey, every error table produced by `call`/`pcall` also carries `ignore_error_stats_update = true`.
   - The rewrites apply to `call`/`pcall` replies only, not to the other delegated operations.
5. **A nested error fails the whole call** (Valkey's `call_ctx->error`, `script_lua.c:1200`). If an error appears
   anywhere in a `call`/`pcall` reply, including inside an array (e.g. an `EXEC` reply with a failed command):
   - each error element becomes `{err=..., ignore_error_stats_update=true}` in place;
   - `redis.call` raises, with the **whole converted reply** as the error value (Valkey raises the top of stack,
     which is the outer reply, not the nested error table);
   - `redis.pcall` returns the whole converted reply.
   Read from the source, not yet run against a real Valkey; a test should pin it, and if Valkey behaves differently,
   Valkey wins.
6. **`ValkeyLua` catches any exception** thrown by a handler and turns it into `-ERR <message>`, so a buggy
   mapping becomes a script error instead of unwinding past Lua. (A Java exception unwinding out of a Wasm host
   call skips `lua_close` and leaks the `lua_State`. Believed, not yet tested; a test should pin it.)
7. Uncaught Lua errors end `eval` with `Result(ok=false, message)`, where message is the error table's `err`
   field (current behaviour, unchanged).

## Handler interface (Java)

Interface shape is my call (one interface, `call` abstract, the rest default methods). The per-eval reset
and error rewrite rules were decided with the user.
It stays a functional interface, so existing `new ValkeyLua(wasm, args -> ...)` callers keep working.

```java
public interface Handler {
    byte[] call(List<byte[]> args);                        // RESP2 reply, as today
    default byte[] setResp(int version) { ... }            // default: +OK for 2, "-ERR RESP3 is not supported" for 3
    default byte[] setRepl(int flags) { ... }              // default: +OK (no-op)
    default byte[] log(int level, String message) { ... }  // default: +OK (drop)
    default byte[] aclCheckCmd(List<byte[]> args) { ... }  // default: :1 (allowed)
}
```

- `setResp`/`setRepl` receive only values that passed Valkey's checks. Like Valkey's run context, the state they
  set applies to the rest of the current `eval`. The handler holds that state.
- **Per-eval reset** (decided with the user): before running each script, `ValkeyLua` calls `handler.setResp(2)` and
  `handler.setRepl(REPL_ALL)`, mirroring Valkey's reset of `resp = 2` and `replication_flags = PROPAGATE_AOF |
  PROPAGATE_REPL` at the start of every run (`script_lua.c:2060`). No extra lifecycle method.
  - Handlers must expect these calls even when the script never calls `setresp`/`set_repl`.
  - If either reset call returns an error reply, `eval` fails with `Result(ok=false, <err>)` without running the script.
  - The README must state this, since a handler that logs or counts these calls will see them on every `eval`.
- `log` receives the level constant and the arguments joined with single spaces, as Valkey's `luaLogCommand` does.
- `aclCheckCmd` returns `:1`/`:0` (→ Lua `true`/`false`) or an error reply. Valkey raises
  `Invalid command passed to server.acl_check_cmd()` for unknown commands; with the handler owning the command
  table, that check is the handler's.
- `setResp(3)` failing is a deliberate divergence from Valkey (which accepts 3) while the module parses only
  RESP2. Script-visible result: `{err="ERR RESP3 is not supported"}` raised (my call on the text).
- Server identity (`redisVersion`, `valkeyVersion`, `serverName`) is passed to the `ValkeyLua` constructor once.
  `*_VERSION_NUM` is computed with Valkey's `parse_semver` (`engine_lua.c`). My call: the existing
  `ValkeyLua(wasm, handler)` constructor stays and defaults to the pinned Valkey's `src/version.h` values
  (`REDIS_VERSION` `7.2.4`, `VALKEY_VERSION` `255.255.255`, `SERVER_NAME` `valkey`).

`eval` becomes `eval(String script, List<byte[]> keys, List<byte[]> args)`. Keep a `eval(String)` overload with
empty keys/args.

## Constraints

- RESP2 only between module and host for now.
- Every Wasm import that returns data follows `redis_call`'s ownership rule: host allocates via the exported
  `alloc`, writes pointer/length to out-params, C side frees.
- Ported Valkey C keeps Valkey's names and comments where practical so it can be diffed against upstream.
- New Valkey sources (`sha1.c`, `rand.c`) are fetched by the Makefile from the same pinned tarball.

## Left to the builder

- Wasm import shapes for the new delegated operations (one import per operation or a shared one), provided the
  ownership rule above holds.
- How server identity and keys/args cross into the module (export arguments, an init export, RESP-encoded buffer).
- Test layout. Each goal and each row of the reply contract tables needs at least one test in `ValkeyLuaTest`.
