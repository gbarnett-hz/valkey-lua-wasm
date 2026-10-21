package valkeylua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValkeyLuaTest {

    /** Stub redis.call backend: records each command and answers with {@link #reply}. */
    private final List<List<String>> calls = new ArrayList<>();
    private String reply = "+OK\r\n";

    private final ValkeyLua lua = new ValkeyLua(args -> {
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
        for (String bad : List.of("", "hello", "$5\r\nab", "*2\r\n:1\r\n", "+OK\r\ngarbage", "+OK\r\n+OK\r\n",
                ":9223372036854775808\r\n", ":-9223372036854775809\r\n", "*1\r\n".repeat(10000) + ":1\r\n")) {
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
    void fullLongLongRangeIntegerReplies() {
        reply = ":9223372036854775807\r\n";
        assertEquals("true", ok("return tostring(redis.call('X') > 9e18)"));
        reply = ":-9223372036854775808\r\n";
        assertEquals("true", ok("return tostring(redis.call('X') < -9e18)"));
    }

    @Test
    void nestedReplyUpToDepthLimitConverts() {
        reply = "*1\r\n".repeat(200) + ":1\r\n";
        assertEquals("1", ok("local r = redis.call('X') for i = 1, 200 do r = r[1] end return r"));
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
    void numberArgumentsFormatLikeValkey() {
        ok("return redis.call('X', 10, -5, 3.0, 0.1, 2.5)");
        assertEquals(List.of("X", "10", "-5", "3", "0.1", "2.5"), calls.get(0));
    }

    @Test
    void argumentErrorMessagesMatchValkey() {
        assertEquals("ERR Please specify at least one argument for this call", lua.eval("return redis.call()").value());
        assertEquals("ERR Command arguments must be strings or integers", lua.eval("return redis.call('SET', {})").value());
        assertEquals("ERR Command arguments must be strings or integers", ok("return redis.pcall('SET', {}).err"));
        assertEquals(List.of(), calls);
    }

    @Test
    void syntaxError() {
        var r = lua.eval("return +");
        assertEquals(false, r.ok());
    }

    @Test
    void handlerExceptionBecomesScriptErrorAndInstanceSurvives() {
        var l = new ValkeyLua(args -> { throw new IllegalStateException("mapping broke"); });
        var r = l.eval("return redis.call('GET', 'k')");
        assertEquals(false, r.ok());
        assertEquals("ERR mapping broke", r.value());
        assertEquals("ERR mapping broke", okOn(l, "return redis.pcall('GET', 'k').err"));
    }

    @Test
    void exceptionMessageWithNewlinesStaysOneError() {
        var l = new ValkeyLua(args -> { throw new RuntimeException("a\r\nb"); });
        assertEquals("ERR a  b", okOn(l, "return redis.pcall('GET', 'k').err"));
    }

    @Test
    void exceptionWithoutMessageUsesClassName() {
        var l = new ValkeyLua(args -> { throw new NullPointerException(); });
        assertEquals("ERR NullPointerException", okOn(l, "return redis.pcall('GET', 'k').err"));
    }

    @Test
    void handlerReturningNullIsScriptError() {
        var l = new ValkeyLua(args -> null);
        for (int i = 0; i < 100; i++) {
            assertEquals("ERR handler returned null", okOn(l, "return redis.pcall('GET', 'k').err"));
        }
        assertEquals("2", okOn(l, "return 1 + 1"));
    }

    @Test
    void handlerErrorBecomesScriptError() {
        var l = new ValkeyLua(args -> { throw new StackOverflowError(); });
        assertEquals("ERR StackOverflowError", okOn(l, "return redis.pcall('GET', 'k').err"));
        var l2 = new ValkeyLua(args -> { throw new AssertionError("bad"); });
        assertEquals("ERR bad", okOn(l2, "return redis.pcall('GET', 'k').err"));
    }

    @Test
    void escapedExceptionPoisonsInstance() {
        assertThrows(NullPointerException.class, () -> lua.eval("return 1", (byte[]) null));
        var e = assertThrows(IllegalStateException.class, () -> lua.eval("return 1"));
        assertEquals("ValkeyLua instance is unusable after an escaped exception; create a new one", e.getMessage());
    }

    @Test
    void luajavaBridgeIsNotReachableFromScripts() {
        assertEquals("nil nil", ok("return tostring(java) .. ' ' .. tostring(debug.getregistry()._LOADED.java)"));
    }

    @Test
    void failingTostringPoisonsInsteadOfAbortingTheJvm() {
        var e = assertThrows(IllegalStateException.class,
                () -> lua.eval("return setmetatable({}, {__tostring = function() error('no') end})"));
        assertTrue(e.getMessage().contains("no"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> lua.eval("return 1"));
    }

    @Test
    void binaryCallArgumentsReachHandlerExactly() {
        var got = new ArrayList<byte[]>();
        var l = new ValkeyLua(args -> { got.addAll(args); return "+OK\r\n".getBytes(StandardCharsets.UTF_8); });
        okOn(l, "return redis.call('SET', 'a\\0b', 'x\\r\\ny').ok");
        assertEquals(3, got.size());
        assertTrue(java.util.Arrays.equals(b("SET"), got.get(0)));
        assertTrue(java.util.Arrays.equals(new byte[] {'a', 0, 'b'}, got.get(1)));
        assertTrue(java.util.Arrays.equals(new byte[] {'x', '\r', '\n', 'y'}, got.get(2)));
    }

    @Test
    void sha1hex() {
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", ok("return redis.sha1hex('hello')"));
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", ok("return redis.sha1hex('')"));
        assertEquals("ERR wrong number of arguments", lua.eval("return redis.sha1hex()").value());
    }

    @Test
    void errorAndStatusReply() {
        assertEquals("ERR x", ok("return redis.error_reply('ERR x').err"));
        assertEquals("ERR x", ok("return redis.error_reply('-ERR x').err"));
        assertEquals("FINE", ok("return redis.status_reply('FINE').ok"));
        // Valkey quirk: bad arguments return an error table instead of raising.
        assertEquals("ERR wrong number or type of arguments", ok("return redis.error_reply(1).err"));
        assertEquals("ERR wrong number or type of arguments", ok("return redis.status_reply().err"));
    }

    @Test
    void debuggerStubsAndReplicateCommands() {
        assertEquals("false", ok("return tostring(redis.breakpoint())"));
        assertEquals("0", ok("return select('#', redis.debug('x', 1))"));
        assertEquals("true", ok("return tostring(redis.replicate_commands())"));
    }

    @Test
    void constants() {
        assertEquals("0 1 2 2 3", ok("return redis.REPL_NONE..' '..redis.REPL_AOF..' '..redis.REPL_SLAVE..' '..redis.REPL_REPLICA..' '..redis.REPL_ALL"));
        assertEquals("0 1 2 3", ok("return redis.LOG_DEBUG..' '..redis.LOG_VERBOSE..' '..redis.LOG_NOTICE..' '..redis.LOG_WARNING"));
        assertEquals("7.2.4 459268 255.255.255 16777215 valkey", ok(
            "return redis.REDIS_VERSION..' '..redis.REDIS_VERSION_NUM..' '..redis.VALKEY_VERSION..' '..redis.VALKEY_VERSION_NUM..' '..redis.SERVER_NAME"));
        var custom = new ValkeyLua(args -> "+OK\r\n".getBytes(StandardCharsets.UTF_8),
                new ServerInfo("7.0.1", "8.1.0", "acme"));
        assertEquals("7.0.1 458753 8.1.0 524544 acme", okOn(custom,
            "return redis.REDIS_VERSION..' '..redis.REDIS_VERSION_NUM..' '..redis.VALKEY_VERSION..' '..redis.VALKEY_VERSION_NUM..' '..redis.SERVER_NAME"));
    }

    /** Records every handler call as a string and answers each op with a settable reply. */
    static class RecordingHandler implements Handler {
        final List<String> ops = new ArrayList<>();
        String setRespReply, setReplReply, logReply, aclReply;

        private static String str(List<byte[]> args) {
            return String.join(" ", args.stream().map(a -> new String(a, StandardCharsets.UTF_8)).toList());
        }

        private static byte[] or(String reply, byte[] dflt) {
            return reply == null ? dflt : reply.getBytes(StandardCharsets.UTF_8);
        }

        @Override public byte[] call(List<byte[]> args) {
            ops.add("call " + str(args));
            return "+OK\r\n".getBytes(StandardCharsets.UTF_8);
        }

        @Override public byte[] setResp(int v) {
            ops.add("setResp " + v);
            return or(setRespReply, Handler.super.setResp(v));
        }

        @Override public byte[] setRepl(int f) {
            ops.add("setRepl " + f);
            return or(setReplReply, Handler.super.setRepl(f));
        }

        @Override public byte[] log(int level, String m) {
            ops.add("log " + level + " " + m);
            return or(logReply, Handler.super.log(level, m));
        }

        @Override public byte[] aclCheckCmd(List<byte[]> args) {
            ops.add("acl " + str(args));
            return or(aclReply, Handler.super.aclCheckCmd(args));
        }
    }

    @Test
    void perEvalResetPrecedesScript() {
        var h = new RecordingHandler();
        okOn(new ValkeyLua(h), "return redis.call('GET', 'k')");
        assertEquals(List.of("setResp 2", "setRepl 3", "call GET k"), h.ops);
    }

    @Test
    void resetErrorFailsEvalWithoutRunningScript() {
        var h = new RecordingHandler();
        h.setReplReply = "-ERR no replication here\r\n";
        var r = new ValkeyLua(h).eval("return redis.call('GET', 'k')");
        assertEquals(false, r.ok());
        assertEquals("ERR no replication here", r.value());
        assertEquals(List.of("setResp 2", "setRepl 3"), h.ops);
    }

    @Test
    void resetRunsEvenAfterFailedEval() {
        var h = new RecordingHandler();
        var l = new ValkeyLua(h);
        l.eval("redis.set_repl(redis.REPL_NONE) error('boom')");
        h.ops.clear();
        okOn(l, "return 1");
        assertEquals(List.of("setResp 2", "setRepl 3"), h.ops);
    }

    @Test
    void setresp() {
        var h = new RecordingHandler();
        var l = new ValkeyLua(h);
        assertEquals("0", okOn(l, "return select('#', redis.setresp(2))"));
        assertEquals("ERR RESP3 is not supported", l.eval("redis.setresp(3)").value());
        assertEquals("ERR RESP version must be 2 or 3.", l.eval("redis.setresp(4)").value());
        assertEquals("ERR server.setresp() requires one argument.", l.eval("redis.setresp()").value());
        h.setRespReply = ":1\r\n"; // now the per-eval reset itself fails, with the same message
        assertEquals("ERR unexpected reply from handler for server.setresp", l.eval("redis.setresp(2)").value());
    }

    @Test
    void setRepl() {
        var h = new RecordingHandler();
        var l = new ValkeyLua(h);
        okOn(l, "redis.set_repl(redis.REPL_AOF) return 1");
        assertTrue(h.ops.contains("setRepl 1"), h.ops.toString());
        assertEquals("ERR Invalid replication flags. Use REPL_AOF, REPL_REPLICA, REPL_ALL or REPL_NONE.",
            l.eval("redis.set_repl(8)").value());
        assertEquals("ERR server.set_repl() requires one argument.", l.eval("redis.set_repl()").value());
    }

    @Test
    void log() {
        var h = new RecordingHandler();
        var l = new ValkeyLua(h);
        okOn(l, "redis.log(redis.LOG_NOTICE, 'a', 'b', 3) return 1");
        assertTrue(h.ops.contains("log 2 a b 3"), h.ops.toString());
        assertEquals("ERR server.log() requires two arguments or more.", l.eval("redis.log(1)").value());
        assertEquals("ERR First argument must be a number (log level).", l.eval("redis.log('x', 'y')").value());
        assertEquals("ERR Invalid log level.", l.eval("redis.log(9, 'y')").value());
    }

    @Test
    void aclCheckCmd() {
        var h = new RecordingHandler();
        var l = new ValkeyLua(h);
        assertEquals("true", okOn(l, "return tostring(redis.acl_check_cmd('GET', 'k'))"));
        h.aclReply = ":0\r\n";
        assertEquals("false", okOn(l, "return tostring(redis.acl_check_cmd('GET', 'k'))"));
        h.aclReply = "-ERR Invalid command passed to server.acl_check_cmd()\r\n";
        assertEquals("ERR Invalid command passed to server.acl_check_cmd()", l.eval("redis.acl_check_cmd('NOPE')").value());
        h.aclReply = "+OK\r\n";
        assertEquals("ERR unexpected reply from handler for server.acl_check_cmd", l.eval("redis.acl_check_cmd('GET')").value());
        assertEquals("ERR Please specify at least one argument for this call", l.eval("redis.acl_check_cmd()").value());
    }

    @Test
    void mathRandomMatchesValkey() {
        assertEquals("75 13", ok("math.randomseed(42) return math.random(100) .. ' ' .. math.random(10, 20)"));
        // luaL_error from a C function: luaL_where(1) prefixes the calling script's position; no ERR prefix.
        assertEquals("user_script:1: wrong number of arguments", lua.eval("return math.random(1, 2, 3)").value());
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void keysAndArgv() {
        var r = lua.eval("return #KEYS .. ' ' .. KEYS[1] .. ' ' .. #ARGV .. ' ' .. ARGV[2]",
            List.of(b("k1")), List.of(b("a1"), b("a2")));
        assertEquals("1 k1 2 a2", r.value());
        assertEquals("0 0", ok("return #KEYS .. ' ' .. #ARGV"));
    }

    @Test
    void keysAndArgvAreBinarySafe() {
        var r = lua.eval("return #KEYS[1] .. ' ' .. #ARGV[1]", List.of(b("a\0b")), List.of(b("x\r\ny")));
        assertEquals("3 4", r.value());
    }

    @Test
    void malformedKeysArgvBufferIsAnError() {
        for (String bad : new String[] {"", "*3\r\n", "*2\r\n*1\r\n$5\r\nab\r\n*0\r\n"}) {
            var r = lua.eval("return 1", bad.getBytes(StandardCharsets.UTF_8));
            assertEquals(false, r.ok());
            assertEquals("malformed KEYS/ARGV buffer", r.value());
        }
        assertEquals("2", ok("return 1 + 1"));
    }

    /** The README's worked example; keep the two in sync. */
    @Test
    void readmeExample() {
        var store = new java.util.HashMap<String, String>();
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
        var vl = new ValkeyLua(handler);
        var r = vl.eval("redis.call('SET', KEYS[1], ARGV[1]); return redis.call('GET', KEYS[1])",
                List.of("k".getBytes()), List.of("v".getBytes()));
        assertEquals(new ValkeyLua.Result(true, "v"), r);
        assertEquals(false, vl.eval("return redis.call('NOPE')").ok());
        // Not in the README: the bulk length must be in bytes for non-ASCII values.
        assertEquals(new ValkeyLua.Result(true, "\u00e9"), vl.eval("redis.call('SET', 'k', ARGV[1]); return redis.call('GET', 'k')",
                List.of(), List.of("\u00e9".getBytes(UTF_8))));
    }
}
