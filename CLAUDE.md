# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

POC: Valkey's bundled Lua 5.1 (with cjson, cmsgpack, struct, bit) run from Java, with the `server.*`/`redis.*` API
delegated to a Java `Handler`. Two backends behind one API; the goal is scripts behaving exactly as on Valkey.

## Commands

```sh
make -C lua      # wasi-sdk 34 + pinned Valkey sources → lua/lua.wasm (patches applied automatically)
make -C native   # luajava JNI glue + Valkey sources → native/build/liblua51<arch>.<dylib|so>
cd java && mvn test                                        # whole suite, both backends (needs both builds above)
cd java && mvn test -Dtest='WasmValkeyLuaTest#testName'    # one test, one backend
cd java && mvn test -Dtest='*ValkeyLuaTest#testName'       # one shared test, both backends
docker build --platform linux/amd64 -t valkey-lua . && docker run --rm --platform linux/amd64 valkey-lua  # Linux x86_64
```

- macOS 27 Command Line Tools: `make -C native CXXFLAGS="-isystem $(xcrun --show-sdk-path)/usr/include/c++/v1"`.
- `make -C lua` re-extracts Valkey by itself when `VALKEY_SHA` or a `lua/patches/*.patch` changes. `native/` does not:
  after changing its sources, `rm -rf native/.deps/valkey` (or `native/.deps/luajava` after changing its luajava
  patch step).
- `VALKEY_SHA` is pinned in both `lua/Makefile` and `native/Makefile`; keep them equal.

## Architecture

- `ValkeyLua` (sealed abstract base) owns what both backends share: `KEYS`/`ARGV` encoding (as a RESP2 `*2` buffer),
  `host()` (any `Throwable` or `null` from the handler becomes an `-ERR` reply), and poisoning (anything escaping
  `eval` makes every later `eval` throw `IllegalStateException`). Backends implement `evalUnchecked`.
- `WasmValkeyLua`: `lua/lua.wasm` on Chicory's runtime compiler. The `server.*` API is C in `lua/valkey_lua.c`, a port
  of Valkey's `script_lua.c`. Every delegated call crosses one import, `env.host_call(op, req, out)`, with a RESP2
  request; the op codes in `WasmValkeyLua` must stay in sync with the enum in `valkey_lua.c`.
- `JniValkeyLua`: native Valkey Lua through luajava 4.0.2 (the Infinispan setup). The `server.*` API is a Java port of
  `valkey_lua.c` against the raw Lua C API, kept structurally parallel to the C. `native/valkey_jni.c` adds the natives
  luajava lacks (`openValkeyLibs`, binary-safe `pushlstring`, `fpconvDtoa`); their JNI symbol names encode the class
  name `JniValkeyLua`, so renaming the class means renaming them.
- JNI rules: a Lua error is a C++ exception and must never unwind through Java frames. Callbacks raise by returning
  `-1` with the error on the stack, and lauxlib error helpers are rebuilt in Java. Every Java→Lua call is protected
  (an unprotected error reaches luajava's panic handler and kills the JVM). luajava's `java` global is removed per state.
- `JniValkeyLua` runs each eval on a pooled 8 MiB-stack thread (overflowing a native stack kills the JVM; Linux's 8 KiB
  `BUFSIZ` made the 1 MiB default overflow before Valkey's limits), so the `Handler` runs off the caller's thread.
- Globals protection is C shared by both builds (`lua/valkey_globals.c`); it runs after setup, so a global added
  during setup must be on its allow list or it is dropped.
- Valkey's EVAL error handler, the reply conversion and error formatting are also shared C (`lua/valkey_reply.c`),
  so `Result.reply` is byte-identical on both backends; JNI reaches it through natives in `native/valkey_jni.c`.
- Each `eval` runs in a fresh `lua_State` on both backends. Not thread-safe.

## Fidelity rule and tests

- The JNI backend (64-bit, unpatched Valkey sources) is the reference for Valkey's behaviour. The Wasm backend must
  match it; anything it can't match is a deviation and must be documented with what a script observes, in
  `gaps.md` and the README's "Where the backends differ".
- `lua.wasm` is wasm32. `lua/patches/` and two `CFLAGS` switches (`-DLUA_USELONGLONG`, `-DSTRUCT_INT='long long'`) make
  every value derived from a Lua integer 64-bit, as on Valkey; it links an 8 MiB C stack. Patches are applied with
  `patch -F 0`, so one that no longer applies exactly fails the build. Patching only some sites makes string/struct
  positions worse than unpatched (see `gaps.md` section 1).
- `ValkeyLuaTest` is the shared suite holding Valkey's expected values; `WasmValkeyLuaTest` and `JniValkeyLuaTest`
  only construct their backend, plus backend-only tests. A test that can't pass on Wasm is a documented deviation,
  not a shared test. Take new expected values from the JNI backend.
- Surefire runs with `-Xmx4g` (the Wasm memory-cap test fills Chicory's ~2 GiB memory) and the JVM's default thread
  stack; don't add `-Xss`.
- `readmeExample` mirrors the README's worked example; keep them in sync.

## Docs

- `README.md`: the `Handler` interface, the reply/error contract (RESP2 replies, raise vs. return, Valkey's command
  error rewrites matched on prefix only), and backend notes. Changes to behaviour usually need a README update.
- `gaps.md`: every Wasm/Valkey difference found, its fix and test, what remains, and the alternatives to both backends.
- `docs/superpowers/specs/` and `docs/superpowers/plans/`: design docs and implementation plans.
- `docs/memory-analysis.md`: Wasm vs JNI memory measurements; harness and raw data in `bench/` (JMH + forked
  footprint probe, needs `mvn install` of `java/` first).
