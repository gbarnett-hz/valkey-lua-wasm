package valkeylua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ValkeyLuaTest {

    private final ValkeyLua lua = new ValkeyLua(Path.of("../lua/lua.wasm"));

    private String ok(String script) {
        var r = lua.eval(script);
        assertTrue(r.ok(), () -> "script failed: " + r.value());
        return r.value();
    }

    @Test
    void plainLua() {
        assertEquals("hello 3", ok("return string.format('hello %d', 1 + 2)"));
    }

    @Test
    void cjson() {
        assertEquals("{\"a\":1}", ok("return cjson.encode({a=1})"));
        assertEquals("2", ok("return cjson.decode('[1,2,3]')[2]"));
    }

    @Test
    void cmsgpack() {
        assertEquals("hi", ok("return cmsgpack.unpack(cmsgpack.pack({x='hi'})).x"));
    }

    @Test
    void struct() {
        assertEquals("258", ok("return struct.unpack('>H', struct.pack('>H', 258))"));
    }

    @Test
    void bit() {
        assertEquals("4", ok("return bit.band(12, 6)"));
        assertEquals("ff", ok("return bit.tohex(255, 2)"));
    }

    @Test
    void luaErrorIsReportedAndInstanceSurvives() {
        var r = lua.eval("error('boom')");
        assertEquals(false, r.ok());
        assertTrue(r.value().contains("boom"), r.value());
        // pcall inside the script exercises setjmp/longjmp without leaving Lua.
        assertEquals("false", ok("return tostring(pcall(error, 'x'))"));
        assertEquals("ok", ok("return 'ok'"));
    }

    @Test
    void syntaxError() {
        var r = lua.eval("return +");
        assertEquals(false, r.ok());
    }
}
