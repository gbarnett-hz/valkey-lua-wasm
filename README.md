# wasm-valkey-lua

POC: Valkey's bundled Lua 5.1 (`deps/lua/src`, with cjson, cmsgpack, struct and bit) run from Java with the `server.*`
API delegated to a `Handler`. Two backends behind one API (`ValkeyLua`) and one test suite (`ValkeyLuaTest`):

| | `WasmValkeyLua` | `JniValkeyLua` |
|---|---|---|
| Lua | compiled to wasm32 (`lua/lua.wasm`), run on [Chicory](https://github.com/dylibso/chicory) | native, through [luajava](https://github.com/gudzpoz/luajava) (JNI), as Infinispan does |
| `server.*` API | C (`lua/valkey_lua.c`) inside the module | Java `JFunction`s, a port of `valkey_lua.c` against the raw Lua C API |
| Isolation | Wasm sandbox; nothing native | native code in the JVM; a Lua panic would kill the JVM (guarded, see below) |
| Integer width | 64-bit, as Valkey (patched; pointers and `size_t` stay 32-bit) | 64-bit, as Valkey |
| Build | wasi-sdk | host C/C++ compiler, JNI headers |
| Construct | `new WasmValkeyLua(Path wasm, handler[, info])` | `new JniValkeyLua(handler[, info])` |

Both give 64-bit Valkey's results; the few remaining differences are in [Where the backends differ](#where-the-backends-differ).

```sh
make -C lua            # downloads wasi-sdk 34 + pinned Valkey sources, builds lua/lua.wasm
make -C native         # downloads luajava's JNI glue + pinned Valkey sources, builds native/build/liblua51<arch>.<so|dylib>
cd java && mvn test    # runs ValkeyLuaTest against both backends (WasmValkeyLuaTest, JniValkeyLuaTest)
```

`make -C lua clean` / `make -C native clean` remove the build outputs and keep the downloaded `.deps`. `make -C lua`
re-extracts Valkey's sources by itself when `VALKEY_SHA` or a file in `lua/patches/` changes. `native/` doesn't: after
changing its pinned Valkey sources, `rm -rf native/.deps/valkey`.
On macOS 27 Command Line Tools, clang++ may not find libc++ headers; pass
`CXXFLAGS="-isystem $(xcrun --show-sdk-path)/usr/include/c++/v1"` to `make -C native`.

Both backends run each `eval` in a fresh `lua_State` and are not thread-safe. The shared code (`ValkeyLua`) encodes
`KEYS`/`ARGV`, turns handler exceptions into `-ERR` replies and poisons the instance when anything escapes `eval`.

Agent skills ([superpowers](https://github.com/obra/superpowers)) are tracked by `skills-lock.json` only; the installed
copies (`.agents/`, `.claude/`) are git-ignored. Restore them with `npx skills experimental_install`. The lock pins each
skill's source and content hash, not a commit, so a restore fetches upstream's current version.

## Wasm backend

- Lua errors use `setjmp`/`longjmp`, compiled to standard Wasm exception handling
  (`-mllvm -wasm-enable-sjlj -lsetjmp -mllvm -wasm-use-legacy-eh=false`). Chicory rejects the legacy EH opcodes, so the last flag is required.
- Runs on Chicory's runtime compiler (`lua.wasm` → JVM bytecode at instantiation), not the default interpreter.
- The module exports `eval(src_ptr, src_len, kv_ptr, kv_len) -> status` and `result_ptr`/`result_len`.
- 64-bit integers, as on Valkey: `-DLUA_USELONGLONG`, `-DSTRUCT_INT='long long'` and six patches in `lua/patches/`
  (`luaconf.h`, `lua_struct.c`, `lbaselib.c`, `lstrlib.c`, `lua_cmsgpack.c`, `lapi.c`), applied with `patch -F 0` so one that
  no longer applies exactly fails the build. What each fixes is in [gaps.md](gaps.md).
- 8 MiB C stack (`-Wl,-z,stack-size=8388608`), so Valkey's own nesting limits (cjson's 1000 levels, `C stack
  overflow`) fire before it runs out. Those need at least 512 KiB of JVM thread stack; every JVM default meets this
  (1 MiB on Linux x86_64, 2 MiB on macOS arm64). Recursion Valkey doesn't limit (`cmsgpack.unpack` nesting, pattern
  backtracking) is bounded by the JVM thread stack instead: at 1 MiB, 3000-deep `cmsgpack.unpack` nesting poisons the
  instance where Valkey returns a table. Depths per stack size are in [gaps.md](gaps.md)
  section 7.

## JNI backend

How it maps to Infinispan:

| | Infinispan (`server/resp`) | `JniValkeyLua` |
|---|---|---|
| Java binding | `party.iroiro.luajava:lua51:4.0.2`, `gdx-jnigen-loader:2.5.2` | same |
| JNI glue | luajava 4.0.2, rebuilt in `infinispan/infinispan-lua` (`org.infinispan:infinispan-lua51-platform`) | the same generated sources, taken from that artifact's `-sources.jar` (byte-identical to running jnigen on luajava v4.0.2) |
| Lua | `redis/redis` `deps/lua`, with luajava's `luaconf.h` | Valkey's `deps/lua` at the same SHA as `lua/Makefile`; the one needed `luaconf.h` change (`LUAI_DATA extern`) is in `native/all.cc` |
| cjson, cmsgpack, struct, bit | not available | compiled in, opened by `native/valkey_jni.c` |
| `redis.*` API | Java `JFunction`s | Java `JFunction`s |

What `native/valkey_jni.c` adds to luajava's natives, and why:

- `openValkeyLibs`: luajava's `luaJ_openlib` only knows the stock libraries.
- `pushlstring`: luajava 4.0.2 can only push a Java `String` (modified UTF-8, cut at NUL), so replies, `KEYS` and
  `ARGV` would not be binary-safe. Infinispan has this limitation.
- `fpconvDtoa`: Valkey's number formatting for call arguments.
- `lockGlobals`, `enableReadonlyGlobals`: Valkey's globals protection (`lua/valkey_globals.c`, shared with
  `lua.wasm`); luajava doesn't know the read-only-table API of Valkey's Lua.

JNI-specific hazards handled in `JniValkeyLua`:

- luajava registers a `java` global (reflection over the whole JVM), also in `_LOADED`; both are removed per state.
  luajava's metatables (`__jobject__`, `__jclass__`, ...) sit in the registry, which scripts can't reach now that
  `debug` is gone.
- After every Java call luajava writes a `__jthrowable__` global (a Java object when the call threw), which the
  read-only globals reject. `native/Makefile` patches luajava's glue to keep it in the registry instead.
- Lua errors are C++ exceptions and must never unwind through Java frames, so callbacks raise by returning `-1`
  and the lauxlib error helpers (`luaL_error`, `luaL_checkint`) are rebuilt in Java.
- An unprotected Lua error reaches luajava's panic handler, which calls `FatalError` and kills the JVM. Every call
  from Java into Lua is protected. Building the reply runs protected too (in `lua/valkey_reply.c`, on both
  backends): a raising `__index` on an error table, which would be a Lua panic on Valkey, throws
  `IllegalStateException` and poisons the instance.
- The natives are loaded once per JVM.
- Lua's C code runs on the calling thread's native stack, so a script that recurses deeper than that stack kills the
  JVM (SIGILL), not just the eval. Measured on macOS arm64: nesting `string.gsub` callbacks 250 deep needs more than
  512 KiB (768 KiB and up raise Valkey's `C stack overflow`); `string.find(string.rep('a', 20000),
  string.rep('a?', 20000))` kills the JVM at the 2 MiB default and works at 8 MiB, Valkey's native default.
  Converting a nested return value also recurses once per level, as on Valkey: at 2 MiB, Valkey's
  `-ERR reached lua stack limit` (about 8000 levels) fires first; at 1 MiB (the Linux x86-64 default), about 7000
  levels (`local t = {} for i = 1, 7000 do t = {t} end return t`) kills the JVM. Run JNI evals on threads with
  Valkey-sized (8 MiB) stacks.

## Where the backends differ

Found by diffing probe scripts between the two backends; [gaps.md](gaps.md) has every case, the
fix for each and its test. `lua.wasm` is wasm32, so `lua_Integer`, `long` and `size_t` were 32-bit; the patches above
make every value derived from a Lua integer 64-bit, and the shared tests after the comment "Valkey runs on 64-bit
hosts" in `ValkeyLuaTest` pin Valkey's values on both backends. What remains:

| Deviation | Wasm |
|---|---|
| Memory cap | Chicory allows just under 2 GiB, less the 8 MiB C stack. Past it a script gets the Lua error `not enough memory`, which `pcall` can catch; the instance survives. If building the reply runs out instead (e.g. returning `{s, s}` for a 512 MiB `s`), `eval` throws `IllegalStateException` and poisons the instance. Valkey has no Lua-specific limit. |
| Memory accounting and GC timing | 32-bit pointers make objects smaller, so `collectgarbage('count')` figures, collector timing and weak-table clearing differ. |
| `tostring` address width | 32-bit addresses (`table: 0x1e3f0`). Cosmetic. |
| JVM thread stack bounds deep recursion | Valkey's own nesting limits need 512 KiB (every default meets it). Recursion Valkey doesn't limit (`cmsgpack.unpack` nesting, pattern backtracking, converting a nested return value) is bounded by the JVM stack: at 1 MiB, 3000-deep `cmsgpack.unpack` nesting poisons the instance where Valkey returns a table, and returning a table nested about 3650 deep (4280 at 2 MiB) throws `ChicoryException` and poisons it where Valkey replies (or, from about 8000, sends `reached lua stack limit`). |
| Out-of-range number returned | `(long long)` of the double saturates: `return 2^63` gives `:9223372036854775807`, `1/0` and `-1/0` the int64 limits, `0/0` gives `:0`. That matches JNI and Valkey on arm64; on x86-64 they give `:-9223372036854775808` for all of these (the cast is undefined in C). |
| String collation | Lua compares strings with `strcoll`. `lua.wasm` always uses the C locale (byte order), as do Valkey's official Docker images. A server whose `locale-collate` or environment names a language locale such as `en_US.UTF-8` orders strings differently (`'a' < 'B'`, `table.sort`). JNI follows the JVM process's locale through the host's C library, so it matches Wasm under `C` but not glibc under a language locale. |
| Memory kept after growth | Wasm memory never shrinks: an instance that grew near the cap keeps up to ~2 GiB of JVM heap until discarded. |
| `string.dump` output | Headers record a 4-byte `size_t`, so dumps differ in bytes and length. |
| `pairs` order over table- or function-keyed tables | Those keys hash by address, so iteration order differs. |

Not differences: the `os` library has only `os.clock` in both (Valkey's `loslib.c`), `tostring` of tables and
functions prints 64-bit addresses on JNI (cosmetic), and an error raised while the reply is built (a raising `__index`
on an error table) throws `IllegalStateException` and poisons the instance on both.

## What runs in Lua (before the Handler)

Ported from Valkey's `script_lua.c` (to C in `lua/valkey_lua.c` for Wasm, to Java in `JniValkeyLua` for JNI), so argument checks and error strings match Valkey:

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
- Globals protection (`lua/valkey_globals.c`, from Valkey's `script_lua.c`/`engine_lua.c`): globals are cut to
  Valkey's allow list (so no `print`, `dofile`, `loadfile`, `newproxy`, `setfenv`, `getfenv` or `debug`), then the
  globals table, every table reachable from it and the basic types' metatables become read-only. Setting a global
  raises `Attempt to modify a readonly table`; reading an undefined one raises
  `Script attempted to access nonexistent global variable 'x'`.
- `KEYS` and `ARGV` are set from `eval(script, keys, args)` before the script runs, after the lock, so their
  contents stay writable as on Valkey.
- Uncaught `luaL_error`/`luaL_check*` errors from Lua C functions carry the script position, e.g.
  `user_script:1: wrong number of arguments`; errors raised as `{err=...}` tables (e.g. `ERR RESP version must be 2 or 3.`) do not.

## What is delegated to the Handler

Replies are RESP2. Wasm: every call goes through the single import `env.host_call(op, req_ptr, req_len, out_ptr, out_len)`,
the request a RESP2 array of bulk strings. JNI: the Java `JFunction`s call the handler directly.

| Lua | `Handler` method | Wasm `op` |
|---|---|---|
| `call`, `pcall` | `call(List<byte[]> args)` | 0 |
| `setresp(v)` | `setResp(int version)` | 1 |
| `set_repl(flags)` | `setRepl(int flags)` | 2 |
| `log(level, msg...)` | `log(int level, String message)` (arguments joined with single spaces) | 3 |
| `acl_check_cmd(argv...)` | `aclCheckCmd(List<byte[]> args)` | 4 |

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

`Handler` is a functional interface, so `new JniValkeyLua(args -> ...)` works. `setResp`/`setRepl` state applies to the
rest of the current `eval`; the handler holds it. `aclCheckCmd` replies `:1`/`:0` or an error; checking that the command
exists is the handler's job.

**Per-eval reset.** At the start of every `eval`, before the script runs, `ValkeyLua` calls `setResp(2)` then
`setRepl(REPL_ALL)` (3), as Valkey resets `resp` and the replication flags on each run. A handler
that logs or counts calls sees these on every `eval`, even if the script never calls `setresp`/`set_repl`. If either
returns an error reply, `eval` returns `Result(ok=false, -<err>)` without running the script (no `script: on ...` suffix).

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
var vl = new JniValkeyLua(handler);   // or new WasmValkeyLua(Path.of("lua/lua.wasm"), handler)
var r = vl.eval("redis.call('SET', KEYS[1], ARGV[1]); return redis.call('GET', KEYS[1])",
        List.of("k".getBytes()), List.of("v".getBytes()));   // Result[ok=true, reply=$1\r\nv\r\n]
```

## Script reply

`eval` returns `Result(ok, reply)`. `reply` is the RESP2 reply Valkey would send the client, built by C shared by both
backends (`lua/valkey_reply.c`, ported from `script_lua.c`'s `luaReplyToServerReply` and `luaCallFunction`).

| Script returns | Reply |
|---|---|
| string | bulk `$<len>`, binary-safe |
| number | `:` + the double cast to `long long` (truncates; see "Where the backends differ" for out-of-range values) |
| `true` | `:1` |
| `false` | `$-1`; `:0` after the script called `setresp(3)` (each `eval` starts at RESP 2) |
| `nil`, a function, userdata, a thread | `$-1` |
| table with string `err` | error reply `-<err>`, cut at the first NUL, CR/LF mapped to spaces |
| table with string `ok` | `+<ok>`, cut at the first NUL, CR/LF mapped to spaces |
| table with number `double` | bulk string: `nan`, `inf`, `-inf`, `-0`, the integer when exact, else the shortest round-trip form |
| table with string `big_number` | bulk string, cut at the first NUL, CR/LF mapped to spaces |
| table with `verbatim_string = {format=..., string=...}` | bulk string of `string`, binary-safe |
| table with table `map` / `set` | flat array: key then value per entry / the keys, in `next` order |
| any other table | array of `t[1]`, `t[2]`, ... up to the first `nil`, converted recursively |
| nested more deeply than the Lua stack allows | `-ERR reached lua stack limit` in place of that element |

Fields are checked in that order with `rawget`, so a table's metatable doesn't change the type.

Failures, with `ok=false`:

| Failure | Reply |
|---|---|
| Lua error, e.g. `error('x')` | `-ERR user_script:1: x script: on @user_script:1.` (Valkey's error handler adds `ERR` to non-table errors and the position) |
| Error table, e.g. from `redis.call` or `error({err='WRONGTYPE bad'})` | `-WRONGTYPE bad script: on @user_script:1.`; `-ERR unknown error ...` if `err` is not a string or number |
| Error that skips the handler (`not enough memory`, `error in error handling`) | `-ERR Error running script, <first 100 bytes>` |
| Syntax error | `-ERR Error compiling script (new function): user_script:1: <Lua's message>` |
| Per-eval reset or KEYS/ARGV buffer failed (this port only) | `-<err>` with no position, e.g. `-ERR malformed KEYS/ARGV buffer` |

Error text always has trailing CR/LF trimmed and the rest mapped to spaces. Valkey's error handler is also installed
as the globals `__server__err__handler` and `__redis__err__handler`.

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
   prefix. The handler returns bytes, not `errno`, so the port matches the prefix only (on both backends):

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
   becomes `-ERR handler returned null`. If anything still escapes `eval` (e.g. a raising `__index` on an
   error table while the reply is built, or Chicory's own call-stack limit), that `eval` rethrows and the instance is poisoned: every later `eval` throws
   `IllegalStateException`; create a new `ValkeyLua`.
7. `eval` returns `Result(ok, reply)`: `reply` is the RESP2 reply Valkey would send the client, for success and
   failure. An uncaught error gives `ok=false` and Valkey's error reply, e.g. `error('x')` gives
   `-ERR user_script:1: x script: on @user_script:1.`; a script that *returns* `{err=...}` gives `ok=true` and an
   error reply, as the client can't tell them apart on Valkey either.

## Divergences from Valkey

- Command error rewrites match on the message prefix only (Valkey also checks `errno`), so an unrelated error starting with
  one of the four prefixes is rewritten too.
- `setresp(3)` is rejected by the default handler (`ERR RESP3 is not supported`); Valkey accepts it.
- RESP2 only between Lua and the Handler.

## Not covered

Script timeouts and SCRIPT KILL, RESP3 reply parsing and RESP3 output to the client, FUNCTION/FCALL,
EVAL's error stats (`ignore_error_stats_update`), `lua-enable-insecure-api`, thread safety.
