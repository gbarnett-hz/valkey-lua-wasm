package valkeylua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValkeyLuaTest {

    static final Path WASM = Path.of("../lua/lua.wasm");

    /** Stub redis.call backend: records each command and answers with {@link #reply}. */
    private final List<List<String>> calls = new ArrayList<>();
    private String reply = "+OK\r\n";

    private final ValkeyLua lua = new ValkeyLua(WASM, args -> {
        calls.add(args.stream().map(a -> new String(a, StandardCharsets.UTF_8)).toList());
        return reply.getBytes(StandardCharsets.UTF_8);
    });

    private String ok(String script) {
        var r = lua.eval(script);
        assertTrue(r.ok(), () -> "script failed: " + r.value());
        return r.value();
    }

    private static String okOn(ValkeyLua l, String script) {
        var r = l.eval(script);
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
    void redisCallReachesHostWithArguments() {
        assertEquals("OK", ok("return redis.call('SET', 'k', 'v', 'EX', 10).ok"));
        assertEquals(List.of(List.of("SET", "k", "v", "EX", "10")), calls);
    }

    @Test
    void redisCallReplyTypes() {
        reply = ":42\r\n";
        assertEquals("number 42", ok("local r = redis.call('INCR', 'n') return type(r) .. ' ' .. r"));
        reply = "$3\r\nbar\r\n";
        assertEquals("bar", ok("return redis.call('GET', 'foo')"));
        reply = "$0\r\n\r\n";
        assertEquals("", ok("return redis.call('GET', 'foo')"));
        reply = "$-1\r\n";
        assertEquals("false", ok("return redis.call('GET', 'missing')"));
        reply = "*-1\r\n";
        assertEquals("false", ok("return redis.call('BLPOP', 'q', 1)"));
        reply = "+PONG\r\n";
        assertEquals("PONG", ok("return redis.call('PING').ok"));
        reply = "*3\r\n$1\r\na\r\n:2\r\n*1\r\n$-1\r\n";
        assertEquals("3 a 2 false", ok(
                "local r = redis.call('X') return #r .. ' ' .. r[1] .. ' ' .. r[2] .. ' ' .. tostring(r[3][1])"));
    }

    @Test
    void redisCallRaisesErrorReplyAndPcallReturnsIt() {
        reply = "-WRONGTYPE bad\r\n";
        var r = lua.eval("redis.call('INCR', 'k') return 'not reached'");
        assertEquals(false, r.ok());
        assertEquals("WRONGTYPE bad", r.value());
        assertEquals("WRONGTYPE bad", ok("return redis.pcall('INCR', 'k').err"));
        assertEquals("false WRONGTYPE bad", ok(
                "local ok, e = pcall(redis.call, 'INCR', 'k') return tostring(ok) .. ' ' .. e.err"));
    }

    @Test
    void redisCallArgumentErrors() {
        var r = lua.eval("return redis.call()");
        assertEquals(false, r.ok());
        assertTrue(r.value().contains("Please specify at least one argument"), r.value());
        r = lua.eval("return redis.call('SET', {})");
        assertEquals(false, r.ok());
        assertTrue(r.value().contains("must be strings or integers"), r.value());
        assertEquals(List.of(), calls);
    }

    @Test
    void syntaxError() {
        var r = lua.eval("return +");
        assertEquals(false, r.ok());
    }

    @Test
    void handlerExceptionBecomesScriptErrorAndInstanceSurvives() {
        var l = new ValkeyLua(WASM, args -> { throw new IllegalStateException("mapping broke"); });
        var r = l.eval("return redis.call('GET', 'k')");
        assertEquals(false, r.ok());
        assertEquals("ERR mapping broke", r.value());
        assertEquals("ERR mapping broke", okOn(l, "return redis.pcall('GET', 'k').err"));
    }

    @Test
    void exceptionMessageWithNewlinesStaysOneError() {
        var l = new ValkeyLua(WASM, args -> { throw new RuntimeException("a\r\nb"); });
        assertEquals("ERR a  b", okOn(l, "return redis.pcall('GET', 'k').err"));
    }

    @Test
    void exceptionWithoutMessageUsesClassName() {
        var l = new ValkeyLua(WASM, args -> { throw new NullPointerException(); });
        assertEquals("ERR NullPointerException", okOn(l, "return redis.pcall('GET', 'k').err"));
    }
}
