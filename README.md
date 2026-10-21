# wasm-valkey-lua — `jni-luajava` branch

Comparison branch: the same `ValkeyLua`/`Handler` API and test suite as `main`, but built the way Infinispan runs Lua
(JNI over a native Lua through [luajava](https://github.com/gudzpoz/luajava)) instead of Wasm on Chicory.
Not for merging into `main`.

```sh
make -C native        # downloads luajava's JNI glue + pinned Valkey sources, builds native/build/liblua51<arch>.<so|dylib>
make -C native clean  # removes native/build (keeps the downloaded .deps)
cd java && mvn test   # packages the native library on the classpath and runs the same tests as main
```

On macOS 27 Command Line Tools, clang++ may not find libc++ headers; pass
`CXXFLAGS="-isystem $(xcrun --show-sdk-path)/usr/include/c++/v1"` to `make`.

## How it maps to Infinispan

| | Infinispan (`server/resp`) | This branch |
|---|---|---|
| Java binding | `party.iroiro.luajava:lua51:4.0.2`, `gdx-jnigen-loader:2.5.2` | same |
| JNI glue | luajava 4.0.2, rebuilt in `infinispan/infinispan-lua` (`org.infinispan:infinispan-lua51-platform`) | the same generated sources, taken from that artifact's `-sources.jar` (byte-identical to running jnigen on luajava v4.0.2) |
| Lua | `redis/redis` `deps/lua`, with luajava's `luaconf.h` | Valkey's `deps/lua` at the SHA `main` uses; the one needed `luaconf.h` change (`LUAI_DATA extern`) is in `native/all.cc` |
| cjson, cmsgpack, struct, bit | not available | compiled in, opened by `native/valkey_jni.c` |
| `redis.*` API | Java `JFunction`s | Java `JFunction`s: a port of `main`'s `valkey_lua.c` against the raw Lua C API (`Lua51Natives`) |

What `native/valkey_jni.c` adds to luajava's natives, and why:

- `openValkeyLibs`: luajava's `luaJ_openlib` only knows the stock libraries.
- `pushlstring`: luajava 4.0.2 can only push a Java `String` (modified UTF-8, cut at NUL), so replies, `KEYS` and
  `ARGV` would not be binary-safe. Infinispan has this limitation.
- `fpconvDtoa`: Valkey's number formatting for call arguments.

JNI-specific hazards handled in `ValkeyLua`:

- luajava registers a `java` global (reflection over the whole JVM), also in `_LOADED`; both are removed per state.
  luajava's metatables (`__jobject__`, `__jclass__`, ...) stay visible through `debug.getregistry()`.
- Lua errors are C++ exceptions and must never unwind through Java frames, so callbacks raise by returning `-1`
  and the lauxlib error helpers (`luaL_error`, `luaL_checkint`) are rebuilt in Java.
- An unprotected Lua error reaches luajava's panic handler, which calls `FatalError` and kills the JVM. Every call
  from Java into Lua is protected; where `main` would trap (a failing `__tostring` on the result), this branch throws
  and poisons the instance.

Found by diffing probe scripts against `main`: `lua.wasm` is wasm32, so `lua_Integer` and C `long` are 32-bit there.
`string.format('%d', 2^40)` gives `2147483647` and `struct.pack('<i8', 2^40)` truncates on `main`; this branch, like
64-bit Valkey, gets them right.

- The natives are loaded once per JVM; each `eval` runs in a fresh `lua_State` (`new Lua51()`), as on `main`.
- `os.tmpname` works (native `mkstemp`), unlike on `main`.

## What runs in Lua (before the Handler)

Ported from Valkey's `script_lua.c` (via `main`'s `valkey_lua.c`, into Java), so argument checks and error strings match Valkey:

- `server` global, with `redis` aliased to the same table.
- `sha1hex`, `error_reply`, `status_reply` (on bad arguments these *return* an error table rather than raise, as in Valkey),
  `replicate_commands()` (always `true`), `breakpoint()` (`false`), `debug(...)` (no-op).
- Constants: `REPL_NONE/AOF/SLAVE/REPLICA/ALL`, `LOG_DEBUG/VERBOSE/NOTICE/WARNING` (Valkey's values),
  `REDIS_VERSION`, `REDIS_VERSION_NUM`, `VALKEY_VERSION`, `VALKEY_VERSION_NUM`, `SERVER_NAME`
  (set via `ServerInfo`; the default is `7.2.4` / `255.255.255` / `valkey`).
- Argument validation for every delegated function runs first; the handler only sees requests that passed it.
  `call`/`pcall` arguments follow `luaArgsToServerArgv` (integer numbers print as integers, others via `fpconv_dtoa`).
- Global patches: `pcall` is Valkey's `luaRedisPcall` wrapper (`{err=}` tables become strings); `math.random` and
  `math.randomseed` use Valkey's PRNG, so sequences match Valkey for the same seed.
- `KEYS` and `ARGV` are set from `eval(script, keys, args)` before the script runs.
- Uncaught `luaL_error`/`luaL_check*` errors from Lua C functions carry the script position, e.g.
  `user_script:1: wrong number of arguments`; errors raised as `{err=...}` tables (e.g. `ERR RESP version must be 2 or 3.`) do not.

## What is delegated to the Handler

Called directly from the Java `JFunction`s; replies are RESP2.

| Lua | `Handler` method |
|---|---|
| `call`, `pcall` | `call(List<byte[]> args)` |
| `setresp(v)` | `setResp(int version)` |
| `set_repl(flags)` | `setRepl(int flags)` |
| `log(level, msg...)` | `log(int level, String message)` (arguments joined with single spaces) |
| `acl_check_cmd(argv...)` | `aclCheckCmd(List<byte[]> args)` |

## Handler interface

```java
public interface Handler {
    byte[] call(List<byte[]> args);                        // RESP2 reply; anything thrown becomes -ERR <message>
    default byte[] setResp(int version) { ... }            // default: +OK for 2, -ERR RESP3 is not supported otherwise
    default byte[] setRepl(int flags) { ... }              // default: +OK (ignored)
    default byte[] log(int level, String message) { ... }  // default: +OK (dropped)
    default byte[] aclCheckCmd(List<byte[]> args) { ... }  // default: :1 (allowed)
}
```

`Handler` is a functional interface, so `new ValkeyLua(args -> ...)` works. `setResp`/`setRepl` state applies to the
rest of the current `eval`; the handler holds it. `aclCheckCmd` replies `:1`/`:0` or an error; checking that the command
exists is the handler's job.

**Per-eval reset.** At the start of every `eval`, before the script runs, `ValkeyLua` calls `setResp(2)` then
`setRepl(REPL_ALL)` (3), as Valkey resets `resp` and the replication flags on each run. A handler
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
            return v == null ? "$-1\r\n".getBytes() : ("$" + v.getBytes(UTF_8).length + "\r\n" + v + "\r\n").getBytes(UTF_8);
        default:
            return ("-ERR unknown command '" + cmd + "'\r\n").getBytes();
    }
};
var vl = new ValkeyLua(handler);
var r = vl.eval("redis.call('SET', KEYS[1], ARGV[1]); return redis.call('GET', KEYS[1])",
        List.of("k".getBytes()), List.of("v".getBytes()));   // Result[ok=true, value=v]
```

## Reply contract

Every delegated operation returns a RESP2 reply. `ValkeyLua` turns it into what the script sees, as Valkey does.

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
`aclCheckCmd` or `:5` from `setResp`, `ValkeyLua` raises `{err="ERR unexpected reply from handler for <fn>"}`
(`<fn>` = the Lua function name, e.g. `server.acl_check_cmd`). This is a broken handler, treated like a thrown
exception, never a silently wrong value. An error reply is never "wrong type": it follows the Errors rules.
`call`/`pcall` accept any well-formed RESP2 reply (arrays nested at most 256 deep, Valkey's
`MAX_NESTED_COLLECTIONS_DEPTH`; nothing after the reply); anything else raises
`ERR unexpected reply from handler for server.call`.

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
   message gets an `ERR ` prefix, trailing CRLF trimmed. Errors raised by the argument checks use the same path,
   so `redis.setresp(4)` raises `{err="ERR RESP version must be 2 or 3."}`.
4. **Command error rewrites happen before the script sees them, on prefix alone** (decided with the user). Valkey's `luaProcessReplyError`
   (`script_lua.c:745`) rewrites four command errors before the script sees them, keyed on the server's `errno` plus a
   prefix. The handler returns bytes, not `errno`, so the port matches the prefix only (as on `main`):

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
   - A handler exception's message goes through the same rewrites (rule 6 turns it into `-ERR <message>` first), so
     `throw new RuntimeException("unknown command 'x'")` reaches the script as `ERR Unknown command called from script`.
5. **A nested error fails the whole call** (Valkey's `call_ctx->error`, `script_lua.c:1200`). If an error appears
   anywhere in a `call`/`pcall` reply, including inside an array (e.g. an `EXEC` reply with a failed command):
   - each error element becomes `{err=..., ignore_error_stats_update=true}` in place;
   - `redis.call` raises, with the **whole converted reply** as the error value (Valkey raises the top of stack,
     which is the outer reply, not the nested error table);
   - `redis.pcall` returns the whole converted reply.
   Pinned by `nestedErrorFailsWholeCall`; not yet checked against a real Valkey, and if Valkey behaves differently,
   Valkey wins.
6. **`ValkeyLua` catches anything a handler throws** (any `Throwable`, including `Error`s such as
   `StackOverflowError`) and turns it into `-ERR <message>` (CR/LF become spaces; the class's simple name if there is
   no message), so a buggy mapping becomes a script error instead of unwinding past Lua. A handler returning `null`
   becomes `-ERR handler returned null`. If anything still escapes `eval` (e.g. a failing `__tostring` on the
   result), that `eval` rethrows and the instance is poisoned, as on `main`: every later `eval` throws
   `IllegalStateException`; create a new `ValkeyLua`.
7. Uncaught Lua errors end `eval` with `Result(ok=false, message)`, where message is the error table's `err`
   field (current behaviour, unchanged).

## Divergences from Valkey

- Command error rewrites match on the message prefix only (Valkey also checks `errno`), so an unrelated error starting with
  one of the four prefixes is rewritten too.
- `setresp(3)` is rejected by the default handler (`ERR RESP3 is not supported`); Valkey accepts it.
- RESP2 only between Lua and the Handler.

## Not covered

Globals protection / read-only tables, removing `debug`, script timeouts and SCRIPT KILL, RESP3 reply parsing,
script return value → RESP conversion (eval still returns `tostring` of the result), FUNCTION/FCALL,
EVAL's server-side error formatting (`script: <sha>, on @user_script:1.`), thread safety.
