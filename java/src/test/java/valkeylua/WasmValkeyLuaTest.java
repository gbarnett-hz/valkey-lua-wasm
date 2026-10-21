package valkeylua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WasmValkeyLuaTest extends ValkeyLuaTest {

    static final Path WASM = Path.of("../lua/lua.wasm");

    @Override
    ValkeyLua create(Handler handler, ServerInfo info) {
        return new WasmValkeyLua(WASM, handler, info);
    }

    // lua.wasm is wasm32, so these differ from 64-bit Valkey; see "Where the backends differ" in the README.

    @Override
    @Test
    void stringFormatIntegersAre64Bit() {
        assertEquals("2147483647|-2147483648|ffffffff|FFFFFFFF|37777777777|4294967295",
            ok("return string.format('%d|%i|%x|%X|%o|%u', 2^40, -2^31-1, 2^40, 2^33, 2^35, 2^32)"));
    }

    @Override
    @Test
    void structLongAndSizeTAre64Bit() {
        assertEquals("4 4 4 4", ok("return struct.size('l')..' '..struct.size('L')..' '..struct.size('T')..' '..struct.size('i')"));
        assertEquals("-1", ok("return struct.unpack('>l', struct.pack('>l', 2^40))"));
        assertEquals("-1", ok("return struct.unpack('<i8', struct.pack('<i8', 2^40))"));
    }

    @Override
    @Test
    void intArgumentsKeepLow32BitsOf64BitInteger() {
        // wasm32 saturates the cast to INT_MAX.
        assertTrue(lua.eval("return math.random(2^40)").ok());
        assertEquals("false", ok("math.randomseed(2^40) local a = math.random(100) math.randomseed(0) return tostring(a == math.random(100))"));
        assertEquals("user_script:1: too many results to unpack", lua.eval("return select('#', unpack({}, 1, 2^31))").value());
    }
}
