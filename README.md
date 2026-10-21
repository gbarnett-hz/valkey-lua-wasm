# wasm-valkey-lua

POC: Valkey's bundled Lua 5.1 (`deps/lua/src`, with cjson, cmsgpack, struct and bit) compiled to Wasm and run on [Chicory](https://github.com/dylibso/chicory).

```sh
make -C lua          # downloads wasi-sdk 34 + pinned Valkey sources, builds lua/lua.wasm
cd java && mvn test  # loads lua.wasm in Chicory and runs scripts against each library
```

- Lua errors use `setjmp`/`longjmp`, compiled to standard Wasm exception handling
  (`-mllvm -wasm-enable-sjlj -lsetjmp -mllvm -wasm-use-legacy-eh=false`). Chicory rejects the legacy EH opcodes, so the last flag is required.
- Runs on Chicory's runtime compiler (`lua.wasm` → JVM bytecode at instantiation), not the default interpreter.
- The module exports `eval(ptr, len) -> status` and `result_ptr`/`result_len`; each eval runs in a fresh `lua_State`.
- `redis.call`/`redis.pcall` cross to Java via the `env.redis_call` import: the request is a RESP2 array of bulk strings,
  the host returns a RESP2 reply, mapped to Lua as Redis does (status → `{ok=}`, error → `{err=}`, nil → `false`).
  `redis.call` raises error replies; `redis.pcall` returns them. Pass the handler to `new ValkeyLua(wasm, handler)`.
- Out of scope: rest of `redis.*`, `server.*`, script return → RESP conversion, RESP3, Valkey's global sandboxing, script timeouts, performance.
- `os.tmpname` always errors (WASI has no temp dirs).
