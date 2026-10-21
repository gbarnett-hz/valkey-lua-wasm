package valkeylua;

import java.nio.file.Path;

class WasmValkeyLuaTest extends ValkeyLuaTest {

    static final Path WASM = Path.of("../lua/lua.wasm");

    @Override
    ValkeyLua create(Handler handler, ServerInfo info) {
        return new WasmValkeyLua(WASM, handler, info);
    }
}
