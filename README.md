# wasm-valkey-lua

POC: Valkey's bundled Lua 5.1 (`deps/lua/src`, with cjson, cmsgpack, struct and bit) compiled to Wasm and run on [Chicory](https://github.com/dylibso/chicory).

```sh
make -C lua          # downloads wasi-sdk 34 + pinned Valkey sources, builds lua/lua.wasm
cd java && mvn test  # loads lua.wasm in Chicory and runs scripts against each library
```

- Lua errors use `setjmp`/`longjmp`, compiled to standard Wasm exception handling
  (`-mllvm -wasm-enable-sjlj -lsetjmp -mllvm -wasm-use-legacy-eh=false`). Chicory rejects the legacy EH opcodes, so the last flag is required.
- The module exports `eval(ptr, len) -> status` and `result_ptr`/`result_len`; each eval runs in a fresh `lua_State`.
- Out of scope: `redis.*`/`server.*`, Valkey's global sandboxing, script timeouts, performance.
- `os.tmpname` always errors (WASI has no temp dirs).
