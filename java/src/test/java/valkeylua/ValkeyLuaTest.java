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
                "local ok, e = pcall(redis.call, 'INCR', 'k') return tostring(ok) .. ' ' .. e"));
    }

    @Test
    void redisIsAliasOfServer() {
        assertEquals("true", ok("return tostring(redis == server)"));
        assertEquals("OK", ok("return server.call('SET', 'k', 'v').ok"));
    }

    @Test
    void commandErrorRewrites() {
        reply = "-ERR unknown command 'FOO', with args beginning with: \r\n";
        assertEquals("ERR Unknown command called from script", ok("return redis.pcall('FOO').err"));
        reply = "-ERR wrong number of arguments for 'get' command\r\n";
        assertEquals("ERR Wrong number of args calling command from script", ok("return redis.pcall('GET').err"));
        reply = "-ERR command not allowed\r\n";
        assertEquals("ERR This Valkey command is not allowed from script", ok("return redis.pcall('X').err"));
        reply = "-NOPERM no access to key\r\n";
        assertEquals("ERR ACL failure in script: no access to key", ok("return redis.pcall('GET', 'k').err"));
        reply = "-WRONGTYPE bad\r\n";
        assertEquals("WRONGTYPE bad", ok("return redis.pcall('GET', 'k').err"));
        assertEquals("true", ok("return tostring(redis.pcall('GET', 'k').ignore_error_stats_update)"));
    }

    @Test
    void nestedErrorFailsWholeCall() {
        reply = "*2\r\n+OK\r\n-ERR inner\r\n";
        assertEquals("OK ERR inner true", ok(
            "local r = redis.pcall('EXEC') return r[1].ok .. ' ' .. r[2].err .. ' ' .. tostring(r[2].ignore_error_stats_update)"));
        assertEquals("OK ERR inner", ok(
            "local ok, e = pcall(function() return redis.call('EXEC') end) return e[1].ok .. ' ' .. e[2].err"));
    }

    @Test
    void globalPcallReturnsErrorStrings() {
        reply = "-WRONGTYPE bad\r\n";
        assertEquals("false WRONGTYPE bad", ok(
            "local ok, e = pcall(redis.call, 'INCR', 'k') return tostring(ok) .. ' ' .. e"));
        assertEquals("true 1 2", ok("local ok, a, b = pcall(function() return 1, 2 end) return tostring(ok)..' '..a..' '..b"));
    }

    @Test
    void malformedReplyIsScriptError() {
        for (String bad : List.of("", "hello", "$5\r\nab", "*2\r\n:1\r\n")) {
            reply = bad;
            for (String fn : List.of("call", "pcall")) {
                var r = lua.eval("return redis." + fn + "('GET', 'k')");
                assertEquals(false, r.ok(), bad);
                assertEquals("ERR unexpected reply from handler for server.call", r.value(), bad);
            }
        }
        reply = "+OK\r\n";
        assertEquals("OK", ok("return redis.call('PING').ok"));
    }

    @Test
    void bulkReplyWithCrlfIsBinarySafe() {
        reply = "$5\r\na\r\n\0b\r\n";
        assertEquals("5", ok("return #redis.call('GET', 'k')"));
    }

    @Test
    void largeArrayReply() {
        var sb = new StringBuilder("*10000\r\n");
        for (int i = 0; i < 10000; i++) sb.append(":").append(i).append("\r\n");
        reply = sb.toString();
        assertEquals("10000 9999", ok("local r = redis.call('X') return #r .. ' ' .. r[10000]"));
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
