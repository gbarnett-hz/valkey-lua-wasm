# Eval RESP2 Reply Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `eval` returns the exact RESP2 bytes Valkey would send a client, for successful and failed scripts, on both backends.

**Architecture:** One new shared C file, `lua/valkey_reply.c`, holds Valkey's error handler install, its
`luaReplyToServerReply` port and its error formatting. Like `lua/valkey_globals.c`, it is compiled into `lua.wasm`
and into the JNI natives, so the backends run the same code. `ValkeyLua.Result` changes to carry `byte[] reply`.

**Tech Stack:** C (Valkey's Lua 5.1, wasi-sdk 34, clang for the JNI natives), Java 25, Chicory, luajava 4.0.2,
JUnit 5, Maven.

**Spec:** `docs/superpowers/specs/2026-10-21-eval-resp-reply-design.md`. Read it first: goal numbers below refer to
it, and its tables are the source of truth for reply bytes.

## Global Constraints

- **Reference behaviour.** Port from Valkey at `VALKEY_SHA` `7db2df1`:
  - `src/modules/lua/script_lua.c`: `luaReplyToServerReply` `:354`, `luaExtractErrorInformation` `:1954`, and
    `luaCallFunction`'s error branch `:2124`.
  - `src/modules/lua/engine_lua.c`: `luaStateInstallErrorHandler` `:73`.
  - `src/eval.c:588`.
  - `src/networking.c`: `addReplyErrorLength` `:883`, `addReplyErrorFormatInternal` `:1073`, `addReplyDouble`
    `:1349`.
  - `src/util.c`: `d2string` `:818`, `double2ll` `:786`.

  Fetch with `curl -sSfL https://github.com/valkey-io/valkey/archive/7db2df1f79f45e2fb0f0d4e16514f2010b8c15b9.tar.gz`
  into a scratch directory (not the repo).
- **Expected values.** JNI is the reference, so take expected values from JNI. The values written in this plan are
  derived from the Valkey source. If JNI disagrees with one of them, stop and report it; don't silently change the
  test.
- **Wasm/JNI differences.** A test that can't pass on Wasm is a documented deviation, not a shared test.
- **Stack.** Don't add `-Xss`. Surefire keeps `-Xmx4g`.
- **Builds.**
  - `native/` does not re-extract on source changes. After adding a C file, list it in both Makefiles' `SRCS` /
    `C_SRCS` and in `native/Makefile`'s `build/$(LIB)` dependency line.
  - Rebuild with `make -C lua && make -C native` (macOS 27: add
    `CXXFLAGS="-isystem $(xcrun --show-sdk-path)/usr/include/c++/v1"`).
- **JNI natives.** The JNI symbol names encode the class `JniValkeyLua`.
- **Lua errors and Java frames.** A Lua error must never unwind through Java frames. Every Java→Lua call is
  protected.
- **`readmeExample`.** It mirrors the README worked example; change them together.

## Review Focus

1. **A script that returns a table with a metatable** (e.g. `return setmetatable({}, {__tostring=...})`) converts
   as a plain array (`*0`), with no metamethod run and no escape. Test in Task 3.
2. **Binary data in returned strings, `verbatim_string` and KEYS round-trips** stays byte-exact: NUL and high bytes
   survive. `ok`, `err` and `big_number` are cut at NUL. Tests in Tasks 1 and 2.
3. **An instance stays usable after any error reply**, including compile errors and `error(nil)`. A following
   `return 1` gives `:1`. Test in Task 2.
4. **A raised `{err=...}` from `redis.call` inside `pcall`** is caught by the script's own `pcall`. The message
   handler applies only to the outer script call, so `pcall(redis.call, ...)` results are unchanged. Existing tests
   in Task 1 keep their inner values.
5. **`setresp(3)` state doesn't leak between evals.** Each eval starts at RESP 2, so `return false` gives `$-1`
   again. Test in Task 2.

---

### Task 1: Shared reply/error C, `Result(ok, byte[] reply)`, both backends, existing tests migrated

**Files:**
- Create: `lua/valkey_reply.c`
- Modify:
  - `lua/valkey_lua.c`: `eval` (`:810-895`), `luaSetResp` (`:602`) and `setKeysArgv`'s message (`:796`)
  - `lua/Makefile:17` and `native/Makefile:28-31`
  - `native/valkey_jni.c`
  - `java/src/main/java/valkeylua/ValkeyLua.java`, `WasmValkeyLua.java` (`evalUnchecked`) and `JniValkeyLua.java`
    (`evalUnchecked` `:488-575`, `luaSetResp` `:306` and `setKeysArgv` `:464`)
  - `java/src/test/java/valkeylua/ValkeyLuaTest.java` and `WasmValkeyLuaTest.java`
  - `README.md`: `Result` mentions (`:169`, `:190`, `:274`) and the worked example

**Interfaces (Produces):**
- C, in `lua/valkey_reply.c`. Declare the prototypes at the top of each consumer, as `luaLockGlobals` is declared
  today.
  - `void luaInstallErrorHandler(lua_State *L);`
    - Loads Valkey's error-handler chunk verbatim, with chunk name `@err_handler_def`.
    - Stores the handler in the registry as `"__ERROR_HANDLER__"`, and sets the globals `__server__err__handler`
      and `__redis__err__handler` to it.
    - Must run before `luaLockGlobals`.
  - `void luaPushErrorHandler(lua_State *L);`: pushes the registry handler.
  - `void luaSetScriptResp(lua_State *L, int resp);`: records the accepted `setresp` version in the registry
    (key `"__SCRIPT_RESP__"`). If it was never set, the version is 2.
  - `typedef struct { char *buf; size_t len, cap; } respBuf;`: `malloc`/`realloc`-grown. The caller frees `buf`.
  - `enum { EVAL_OK = 0, EVAL_SETUP = 1, EVAL_COMPILE = 2, EVAL_RUN = 3 };`
  - `int luaEvalReply(lua_State *L, int kind, respBuf *out);`
    - Consumes the top value: the return value for `EVAL_OK`, the error object otherwise.
    - Appends one RESP2 reply. Returns 0, or -1 if the protected conversion itself raised (the escape).
    - Runs the conversion protected. Push a C function, the value and a lightuserdata to `out`, then
      `lua_pcall(L, 2, 0, 0)`.
    - Formatting per kind:
      - `EVAL_OK`: spec goal 2.
      - `EVAL_RUN`: goal 3's raised-table and raised-non-table rules.
      - `EVAL_COMPILE`: `"ERR Error compiling script (new function): " + msg`.
      - `EVAL_SETUP`: a table gives its `err`, a string gives itself, with no suffix.
    - All error text then goes through one `addReplyErrorFormatInternal`-equivalent: `-` + text, trailing CR/LF
      trimmed, inner CR/LF → space, and `ERR ` added only when the text doesn't start with `-`. Compile and setup
      text never starts with `-` after the prefix, so their `-ERR ` comes from that rule, as on Valkey.
- C, in `lua/valkey_lua.c`: `eval` returns `0` (ok), `1` (Lua failure, reply is an error) or `-1` (escape). The
  reply stays in `result_ptr`/`result_len`.
- JNI natives in `native/valkey_jni.c` (`Java_valkeylua_JniValkeyLua_*`):
  - `static native void installErrorHandler(long L);`
  - `static native void pushErrorHandler(long L);`
  - `static native void setScriptResp(long L, int resp);`
  - `static native byte[] evalReply(long L, int kind);`: returns `null` on escape.
- Java:
  - `public record Result(boolean ok, byte[] reply)`, overriding `equals`/`hashCode` (`Arrays`) and `toString`
    (`Result[ok=…, reply=…]`, the reply as ISO-8859-1 with `\r`/`\n` shown as `\\r`/`\\n`).
  - Both backends throw `IllegalStateException("reply conversion failed")` on escape.
- Test helpers in `ValkeyLuaTest`, used by Tasks 2-3:
  - `String ok(String script)` asserts `ok` and returns the reply as ISO-8859-1. `String err(String script)` does
    the same and asserts `!ok`.
  - Static builders: `bulk(String)`, `integer(long)`, `status(String)`, `error(String)` (adds `-` and `\r\n`),
    `array(String... elems)` and `NULL = "$-1\r\n"`.

**Setup order in both `eval`s:**
1. Open the libraries and the `server` API, exactly as today.
2. `luaInstallErrorHandler`.
3. `luaLockGlobals`.
4. Reset (`EVAL_SETUP` on failure).
5. KEYS/ARGV (`EVAL_SETUP`; message becomes `"ERR malformed KEYS/ARGV buffer"`).
6. `luaL_loadbuffer` (`EVAL_COMPILE`).
7. `luaPushErrorHandler`, insert it below the chunk, `lua_pcall(L, 0, 1, <handler index>)` (`EVAL_RUN`).
8. `luaEvalReply`.

Remove the `{err=}` unwrapping and the `tostring` call.

`luaSetResp` (C and Java) calls `luaSetScriptResp` after the handler accepts the version. The Java version calls it
through the `setScriptResp` native.

- [ ] **Step 1: Migrate the shared tests to RESP.** Add the helpers above. Rewrite every assertion to its RESP form:
  - `assertEquals("hello 3", ok(...))` → `assertEquals(bulk("hello 3"), ok(...))`
  - `"2"` from `cjson.decode(...)[2]` → `integer(2)`
  - error-text assertions use `err(...)` with the Valkey format, e.g. line 110:
    `assertEquals(error("WRONGTYPE bad script: on @user_script:1."), err("return redis.call('INCR','k')"))`
    (adapt to the test's actual script)
  - `Result(true, "1")` → `new Result(true, ":1\r\n".getBytes(ISO_8859_1))`

  Error-text values that depend on the handler, such as the `NESTING` table's `C stack overflow` row and the
  memory-cap test, are filled from JNI in Step 5. Leave them as the spec-predicted value meanwhile:
  `-ERR user_script:1: … script: on @user_script:1.`; memory cap
  `-ERR Error running script, not enough memory`.

  Delete `failingTostringPoisonsInstance`; Task 3 replaces it.

  Add:
  ```java
  @Test void returnValueTypes() {
      assertEquals(integer(1), ok("return 1"));
      assertEquals(integer(3), ok("return 3.9"));
      assertEquals(integer(-3), ok("return -3.9"));
      assertEquals(bulk("hello"), ok("return 'hello'"));
      assertEquals(integer(1), ok("return true"));
      assertEquals(NULL, ok("return false"));
      assertEquals(NULL, ok("return nil"));
      assertEquals(NULL, ok("return function() end"));
      assertEquals(array(integer(1), bulk("a"), array(integer(2))), ok("return {1, 'a', {2}}"));
      assertEquals(array(integer(1)), ok("return {1, nil, 3}"));
      assertEquals(status("OK"), ok("return redis.call('SET', 'k', 'v')"));
  }
  @Test void errorReplies() {
      assertEquals(error("ERR user_script:1: x script: on @user_script:1."), err("error('x')"));
      assertEquals(error("boom"), ok("return {err='boom'}"));             // returned, not raised: ok=true
      assertEquals(error("WRONGTYPE bad script: on @user_script:1."), err("error({err='WRONGTYPE bad'})"));
      assertEquals(error("ERR Error compiling script (new function): user_script:1: unexpected symbol near '<eof>'"),
          err("x ="));
      assertEquals(bulk("function"), ok("return type(__server__err__handler)"));
      assertEquals(bulk("true"), ok("return tostring(__redis__err__handler == __server__err__handler)"));
      assertEquals(NULL, ok("return debug"));
  }
  ```
  Update `readmeExample` to expect `new Result(true, "$1\r\nv\r\n".getBytes(ISO_8859_1))`.

- [ ] **Step 2: Run the suite to verify it fails.** Run `cd java && mvn test`. Expected: compilation failure
  (`reply()` undefined) or failures in every RESP assertion.

- [ ] **Step 3: Implement `lua/valkey_reply.c`** per the Interfaces block.
  - Port `luaReplyToServerReply` with the recursion kept (spec: deep nesting is a documented deviation), writing to
    `respBuf` instead of `ValkeyModule_Reply*`.
  - Port `luaExtractErrorInformation` and `d2string`/`double2ll` as they are. Use `fpconv_dtoa`, which is already
    linked into both builds.
  - Number → `(long long)lua_tonumber`. wasi-sdk 34 emits `i64.trunc_sat_f64_s` for this cast (checked), so it
    needs no flag.
  - Add it to `lua/Makefile:17` and `native/Makefile:29` and `:31`. Then `rm -rf native/.deps/valkey` is *not*
    needed, because no Valkey sources change.

- [ ] **Step 4: Wire both backends** per "Setup order". Add the four natives and the `Result` record. Update
  `WasmValkeyLua.evalUnchecked`: status `-1` throws `IllegalStateException("reply conversion failed")`, and the
  reply bytes are read from `result_ptr`/`result_len`.

- [ ] **Step 5: Build and run.** Run
  `make -C lua && make -C native CXXFLAGS="-isystem $(xcrun --show-sdk-path)/usr/include/c++/v1" && cd java && mvn test`.
  Expected: all pass except the handler-dependent values left as predictions in Step 1. For each failure, read
  JNI's actual value:
  - If it is consistent with the spec's rules (e.g. `C stack overflow` turning into
    `error in error handling` via `LUA_ERRERR`), put JNI's value in the test and note the reason in a comment.
  - If JNI and Wasm disagree, stop and report it.

  Rerun until both backends pass the whole suite.

- [ ] **Step 6: Update the README's `Result` description, worked example and `:274` uncaught-errors text** to the
  RESP form. The full "Script reply" section comes in Task 4.

- [ ] **Step 7: Commit.**
  ```bash
  git add lua/valkey_reply.c lua/valkey_lua.c lua/Makefile native/Makefile native/valkey_jni.c java README.md
  git commit -m "Return Valkey's RESP2 reply from eval on both backends"
  ```

### Task 2: Reply edge cases (spec goals 2, 3, 5, 7)

**Files:**
- Test: `java/src/test/java/valkeylua/ValkeyLuaTest.java`, `WasmValkeyLuaTest.java` and `JniValkeyLuaTest.java`
- Modify (only if a test exposes a bug): `lua/valkey_reply.c`

**Interfaces (Consumes):** the Task 1 helpers, `Result.reply()`, and the `Handler.setResp` default.

- [ ] **Step 1: Write the tests.**
  ```java
  @Test void typedTableReplies() {
      assertEquals(status("a  b"), ok("return {ok='a\\r\\nb'}"));
      assertEquals(status("a"), ok("return {ok='a\\0b'}"));
      assertEquals(error("a  b"), ok("return {err='a\\r\\nb\\r\\n'}"));
      assertEquals(error("a"), ok("return {err='a\\0b'}"));
      assertEquals(array(), ok("return {err=42}"));                       // not a string: array case
      assertEquals(bulk("1.5"), ok("return {double=1.5}"));
      assertEquals(bulk("3"), ok("return {double=3}"));
      assertEquals(bulk("nan"), ok("return {double=0/0}"));
      assertEquals(bulk("inf"), ok("return {double=1/0}"));
      assertEquals(bulk("-inf"), ok("return {double=-1/0}"));
      assertEquals(bulk("-0"), ok("return {double=-1/(1/0)}"));
      assertEquals(bulk("0.1"), ok("return {double=0.1}"));
      assertEquals(bulk("123  "), ok("return {big_number='123\\r\\n'}"));
      assertEquals(bulk("a\0b"), ok("return {verbatim_string={format='txt', string='a\\0b'}}"));
      assertEquals(array(bulk("a"), integer(1)), ok("return {map={a=1}}"));
      assertEquals(array(bulk("a")), ok("return {set={a=true}}"));
      assertEquals(array(integer(1), error("e")), ok("return {1, {err='e'}}"));
      assertEquals(bulk("ÿ\0x"), ok("return '\\255\\0x'"));
  }
  @Test void raisedErrorFormats() {
      assertEquals(error("42 script: on @user_script:1."), err("error({err=42})"));
      assertEquals(error("ERR unknown error script: on @user_script:1."), err("error({})"));
      assertEquals(error("ERR nil script: on @user_script:1."), err("error(nil)"));
      assertEquals(error("ERR user_script:2: x script: on @user_script:2."),
          err("local function f()\nerror('x')\nend\nf()"));
      // a newline inside the compile message is mapped to a space; exact text from JNI, must contain "[[a  b]]"
      assertTrue(err("return 1 [[a\nb]]").contains("[[a  b]]"));
      assertEquals(integer(1), ok("return 1"));                           // instance still usable
  }
  @Test void setupFailureHasNoSuffix() {
      var l = create(new Handler() {
          public byte[] call(List<byte[]> a) { return "+OK\r\n".getBytes(UTF_8); }
          public byte[] setRepl(int f) { return "-ERR no replication here\r\n".getBytes(UTF_8); }
      });
      var r = l.eval("return 1");
      assertEquals(false, r.ok());
      assertEquals(error("ERR no replication here"), new String(r.reply(), StandardCharsets.ISO_8859_1));
      assertEquals(error("ERR malformed KEYS/ARGV buffer"),
          new String(lua.eval("return 1", "x".getBytes(UTF_8)).reply(), StandardCharsets.ISO_8859_1));
  }
  @Test void resp3BooleansAndReset() {
      var l = create(new Handler() {
          public byte[] call(List<byte[]> a) { return "+OK\r\n".getBytes(UTF_8); }
          public byte[] setResp(int v) { return "+OK\r\n".getBytes(UTF_8); }
      });
      assertEquals(":0\r\n", okOn(l, "redis.setresp(3) return false"));
      assertEquals(":1\r\n", okOn(l, "redis.setresp(3) return true"));
      assertEquals(NULL, okOn(l, "return false"));                        // each eval starts at RESP 2
  }
  ```
  The number-cast edge cases are backend tests, because x86-64 differs (spec Constraints). Use an `int64Edges`
  helper with these five scripts: `return 2^63`, `return -2^63-1e5`, `return 1/0`, `return -1/0`, `return 0/0`.
  - `WasmValkeyLuaTest.numberCastSaturates()`: expects
    `:9223372036854775807`, `:-9223372036854775808`, `:9223372036854775807`, `:-9223372036854775808`, `:0`
    (each with `\r\n`).
  - `JniValkeyLuaTest.numberCastFollowsHardware()`: expects the same on `os.arch` `aarch64`. On `amd64` or
    `x86_64` it expects `:-9223372036854775808` for all five.

  Take the existing `setRepl`-error test (`:373`) as the model for handler construction if `Handler`'s shape
  differs.

- [ ] **Step 2: Run them.** Run `cd java && mvn test -Dtest='*ValkeyLuaTest'`. Expected: pass. If one fails, fix
  `lua/valkey_reply.c` against the cited Valkey line, not the test. If JNI itself disagrees with a value derived from
  the Valkey source, report it.

- [ ] **Step 3: Commit.**
  ```bash
  git add java lua/valkey_reply.c
  git commit -m "Test Valkey's reply conversion edge cases on both backends"
  ```

### Task 3: Metamethods during conversion (spec goal 8)

**Files:**
- Test: `java/src/test/java/valkeylua/ValkeyLuaTest.java`

- [ ] **Step 1: Write the tests.**
  ```java
  @Test void metatablesDuringConversion() {
      assertEquals(array(), ok("return setmetatable({}, {__tostring = function() error('no') end})"));
      assertEquals(error("E script: on @user_script:1."),
          err("error(setmetatable({}, {__index = function() return 'E' end}))"));
  }
  @Test void raisingIndexOnErrorTablePoisons() {
      var e = assertThrows(IllegalStateException.class,
          () -> lua.eval("return setmetatable({err='x'}, {__index = function() error('boom') end})"));
      assertEquals("reply conversion failed", e.getMessage());
      assertThrows(IllegalStateException.class, () -> lua.eval("return 1"));
  }
  ```
- [ ] **Step 2: Run them.** Run `cd java && mvn test -Dtest='*ValkeyLuaTest#metatables*+raising*'`. Expected:
  pass on both, and the JVM survives. A JVM crash means the conversion is not protected on JNI; fix
  `luaEvalReply`.
- [ ] **Step 3: Commit.**
  ```bash
  git add java
  git commit -m "Pin metamethod behaviour during reply conversion"
  ```

### Task 4: Deep-nesting measurement and docs

**Files:**
- Test: `java/src/test/java/valkeylua/WasmValkeyLuaTest.java`
- Modify: `README.md`, `wasm-difference.md` and `CLAUDE.md`

- [ ] **Step 1: Measure.** Use a throwaway JUnit test or jshell on the test classpath, not committed. On each
  backend, at the default thread stack, find the smallest `n` for which
  `local t={} for i=1,n do t={t} end return t` doesn't return a reply:
  - run JNI in a child JVM, because it may die;
  - Wasm poisons the instance.

  Also record whether either backend reaches `-ERR reached lua stack limit` first. Record OS, arch, JDK and stack
  size.
- [ ] **Step 2: Pin the Wasm failure.** Add `WasmValkeyLuaTest.deepReplyNestingPoisons()`. Run the script at about
  2× the measured `n` on a 1 MiB thread (the pattern of `nestingErrorsAtMinimumThreadStack`). Assert the eval throws
  (record the exception type you observed) and the next eval throws `IllegalStateException`.

  If Wasm reached `reached lua stack limit` instead, assert that reply. Run
  `cd java && mvn test -Dtest='WasmValkeyLuaTest#deepReplyNestingPoisons'`. Expected: PASS.
- [ ] **Step 3: Docs** (spec "Docs"):
  - README:
    - add a "Script reply" section with the spec's goal 2 table, goal 3's error formats and goal 5's setup
      failures;
    - drop "script return value → RESP conversion" and "EVAL's server-side error formatting" from "Not covered";
    - remove the `<sha>` text;
    - reword the escape row to the raising-`__index` cause. Both now throw `IllegalStateException`, so move it to
      "Not differences";
    - add the number-cast row (Wasm saturates; JNI follows hardware, so x86-64 gives `INT64_MIN`);
    - add the deep-nesting hazard, with the Step 1 numbers, beside the existing JNI stack notes.
  - `wasm-difference.md`: the same three changes, in its format and with the test names.
  - CLAUDE.md, Architecture: "Reply conversion and error formatting are C shared by both builds
    (`lua/valkey_reply.c`)."
- [ ] **Step 4: Full suite.** Run `cd java && mvn test`. Expected: BUILD SUCCESS on both backends.
- [ ] **Step 5: Commit.**
  ```bash
  git add java README.md wasm-difference.md CLAUDE.md
  git commit -m "Document reply conversion, its deviations and the deep-nesting hazard"
  ```
