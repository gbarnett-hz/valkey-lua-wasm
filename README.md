# wasm-valkey-lua

POC: Valkey's bundled Lua 5.1 (`deps/lua/src`, with cjson, cmsgpack, struct and bit) compiled to Wasm and run on [Chicory](https://github.com/dylibso/chicory).

```sh
make -C lua          # downloads wasi-sdk 34 + pinned Valkey sources, builds lua/lua.wasm
make -C lua clean    # removes lua/lua.wasm (keeps the downloaded .deps)
cd java && mvn test  # loads lua.wasm in Chicory and runs scripts against each library
```

- Lua errors use `setjmp`/`longjmp`, compiled to standard Wasm exception handling
  (`-mllvm -wasm-enable-sjlj -lsetjmp -mllvm -wasm-use-legacy-eh=false`). Chicory rejects the legacy EH opcodes, so the last flag is required.
- Runs on Chicory's runtime compiler (`lua.wasm` → JVM bytecode at instantiation), not the default interpreter.
- The module exports `eval(src_ptr, src_len, kv_ptr, kv_len) -> status` and `result_ptr`/`result_len`; each eval runs in a fresh `lua_State`.
- Host calls cross to Java via the single `env.host_call` import (see below). Pass the handler to `new ValkeyLua(wasm, handler)`.
- `os.tmpname` always errors (WASI has no temp dirs).

## What runs inside the module

Ported from Valkey's `script_lua.c`, so argument checks and error strings match Valkey:

- `server` global, with `redis` aliased to the same table.
- `sha1hex`, `error_reply`, `status_reply` (on bad arguments these *return* an error table rather than raise, as in Valkey),
  `replicate_commands()` (always `true`), `breakpoint()` (`false`), `debug(...)` (no-op).
- Constants: `REPL_NONE/AOF/SLAVE/REPLICA/ALL`, `LOG_DEBUG/VERBOSE/NOTICE/WARNING` (Valkey's values),
  `REDIS_VERSION`, `REDIS_VERSION_NUM`, `VALKEY_VERSION`, `VALKEY_VERSION_NUM`, `SERVER_NAME`
  (set via `ServerInfo`; the default is `7.2.4` / `255.255.255` / `valkey`).
- Argument validation for every delegated function runs in C first; the handler only sees requests that passed it.
  `call`/`pcall` arguments follow `luaArgsToServerArgv` (integer numbers print as integers, others via `fpconv_dtoa`).
- Global patches: `pcall` is Valkey's `luaRedisPcall` wrapper (`{err=}` tables become strings); `math.random` and
  `math.randomseed` use Valkey's PRNG, so sequences match Valkey for the same seed.
- `KEYS` and `ARGV` are set from `eval(script, keys, args)` before the script runs.
- Uncaught errors from Lua C functions carry the script position, e.g. `user_script:1: wrong number of arguments`.

## What is delegated to the Handler

All go through the single import `env.host_call(op, req_ptr, req_len, out_ptr, out_len)`; the request is a RESP2 array of
bulk strings and the reply is RESP2.

| Lua | `Handler` method | `op` |
|---|---|---|
| `call`, `pcall` | `call(List<byte[]> args)` | 0 |
| `setresp(v)` | `setResp(int version)` | 1 |
| `set_repl(flags)` | `setRepl(int flags)` | 2 |
| `log(level, msg...)` | `log(int level, String message)` (arguments joined with single spaces) | 3 |
| `acl_check_cmd(argv...)` | `aclCheckCmd(List<byte[]> args)` | 4 |

## Handler interface

```java
public interface Handler {
    byte[] call(List<byte[]> args);                        // RESP2 reply; a thrown RuntimeException becomes -ERR <message>
    default byte[] setResp(int version) { ... }            // default: +OK for 2, -ERR RESP3 is not supported otherwise
    default byte[] setRepl(int flags) { ... }              // default: +OK (ignored)
    default byte[] log(int level, String message) { ... }  // default: +OK (dropped)
    default byte[] aclCheckCmd(List<byte[]> args) { ... }  // default: :1 (allowed)
}
```

`Handler` is a functional interface, so `new ValkeyLua(wasm, args -> ...)` works. `setResp`/`setRepl` state applies to the
rest of the current `eval`; the handler holds it. `aclCheckCmd` replies `:1`/`:0` or an error; checking that the command
exists is the handler's job.

**Per-eval reset.** At the start of every `eval`, before the script runs, the module calls `setResp(2)` then
`setRepl(REPL_ALL)` (3) through `host_call`, as Valkey resets `resp` and the replication flags on each run. A handler
that logs or counts calls sees these on every `eval`, even if the script never calls `setresp`/`set_repl`. If either
returns an error reply, `eval` returns `Result(ok=false, <err>)` without running the script.

Worked example (this is the `readmeExample` test in `ValkeyLuaTest`):

```java
var store = new HashMap<String, String>();
Handler handler = args -> {
    String cmd = new String(args.get(0)).toUpperCase();
    switch (cmd) {
        case "SET":
            store.put(new String(args.get(1)), new String(args.get(2)));
            return "+OK\r\n".getBytes();
        case "GET":
            String v = store.get(new String(args.get(1)));
            return v == null ? "$-1\r\n".getBytes() : ("$" + v.length() + "\r\n" + v + "\r\n").getBytes();
        default:
            return ("-ERR unknown command '" + cmd + "'\r\n").getBytes();
    }
};
var vl = new ValkeyLua(Path.of("lua/lua.wasm"), handler);
var r = vl.eval("redis.call('SET', KEYS[1], ARGV[1]); return redis.call('GET', KEYS[1])",
        List.of("k".getBytes()), List.of("v".getBytes()));   // Result[ok=true, value=v]
```

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
   Pinned by `nestedErrorFailsWholeCall`; not yet checked against a real Valkey, and if Valkey behaves differently,
   Valkey wins.
6. **`ValkeyLua` catches any `RuntimeException`** thrown by a handler and turns it into `-ERR <message>`, so a buggy
   mapping becomes a script error instead of unwinding past Lua. (A Java exception unwinding out of a Wasm host
   call skips `lua_close` and leaks the `lua_State`. Instance survival is tested by `handlerExceptionBecomesScriptErrorAndInstanceSurvives`; the leak itself is believed, not tested. An `Error` such as `StackOverflowError` still escapes.)
7. Uncaught Lua errors end `eval` with `Result(ok=false, message)`, where message is the error table's `err`
   field (current behaviour, unchanged).

## Divergences from Valkey

- Command error rewrites match on the message prefix only (Valkey also checks `errno`), so an unrelated error starting with
  one of the four prefixes is rewritten too.
- `setresp(3)` is rejected by the default handler (`ERR RESP3 is not supported`); Valkey accepts it.
- RESP2 only between module and host.

## Not covered

Globals protection / read-only tables, removing `debug`, script timeouts and SCRIPT KILL, RESP3 reply parsing,
script return value → RESP conversion (eval still returns `tostring` of the result), FUNCTION/FCALL,
EVAL's server-side error formatting (`script: <sha>, on @user_script:1.`), thread safety.
