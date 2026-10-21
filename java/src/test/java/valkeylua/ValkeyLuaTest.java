package valkeylua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The contract both backends must meet; each subclass supplies one. */
abstract class ValkeyLuaTest {

    abstract ValkeyLua create(Handler handler, ServerInfo info);

    ValkeyLua create(Handler handler) {
        return create(handler, ServerInfo.DEFAULT);
    }

    /** Stub redis.call backend: records each command and answers with {@link #reply}. */
    private final List<List<String>> calls = new ArrayList<>();
    private String reply = "+OK\r\n";

    final ValkeyLua lua = create(args -> {
        calls.add(args.stream().map(a -> new String(a, StandardCharsets.UTF_8)).toList());
        return reply.getBytes(StandardCharsets.UTF_8);
    });

    String ok(String script) {
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
        var l = create(args -> { throw new IllegalStateException("mapping broke"); });
        var r = l.eval("return redis.call('GET', 'k')");
        assertEquals(false, r.ok());
        assertEquals("ERR mapping broke", r.value());
        assertEquals("ERR mapping broke", okOn(l, "return redis.pcall('GET', 'k').err"));
    }

    @Test
    void exceptionMessageWithNewlinesStaysOneError() {
        var l = create(args -> { throw new RuntimeException("a\r\nb"); });
        assertEquals("ERR a  b", okOn(l, "return redis.pcall('GET', 'k').err"));
    }

    @Test
    void exceptionWithoutMessageUsesClassName() {
        var l = create(args -> { throw new NullPointerException(); });
        assertEquals("ERR NullPointerException", okOn(l, "return redis.pcall('GET', 'k').err"));
    }

    @Test
    void handlerReturningNullIsScriptError() {
        var l = create(args -> null);
        for (int i = 0; i < 100; i++) {
            assertEquals("ERR handler returned null", okOn(l, "return redis.pcall('GET', 'k').err"));
        }
        assertEquals("2", okOn(l, "return 1 + 1"));
    }

    @Test
    void handlerErrorBecomesScriptError() {
        var l = create(args -> { throw new StackOverflowError(); });
        assertEquals("ERR StackOverflowError", okOn(l, "return redis.pcall('GET', 'k').err"));
        var l2 = create(args -> { throw new AssertionError("bad"); });
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
        assertEquals("nil nil", ok("return tostring(rawget(_G, 'java')) .. ' ' .. tostring(rawget(_G, 'debug'))"));
    }

    @Test
    void globalsAreValkeysAllowList() {
        // Valkey also has __server__err__handler and __redis__err__handler (its EVAL error handler, not ported).
        assertEquals("ARGV KEYS _G _VERSION assert bit cjson cmsgpack collectgarbage coroutine error gcinfo "
                + "getmetatable ipairs load loadstring math next os pairs pcall rawequal rawget rawset redis select "
                + "server setmetatable string struct table tonumber tostring type unpack xpcall",
            String.join(" ", java.util.Arrays.stream(
                ok("local t = {} for k in pairs(_G) do t[#t + 1] = k end return table.concat(t, ' ')").split(" "))
                .sorted().toList()));
    }

    @Test
    void globalsAreProtected() {
        for (String[] c : new String[][] {
                {"x = 1", "user_script:1: Attempt to modify a readonly table"},
                {"rawset(_G, 'x', 1)", "Attempt to modify a readonly table"}, // raised in C, so no position
                {"function f() end", "user_script:1: Attempt to modify a readonly table"},
                {"string.x = 1", "user_script:1: Attempt to modify a readonly table"},
                {"getmetatable('').__index = {}", "user_script:1: Attempt to modify a readonly table"},
                {"setmetatable(_G, nil)", "Attempt to modify a readonly table"},
                {"return y", "user_script:1: Script attempted to access nonexistent global variable 'y'"},
                {"return print", "user_script:1: Script attempted to access nonexistent global variable 'print'"},
                {"return debug", "user_script:1: Script attempted to access nonexistent global variable 'debug'"}}) {
            assertEquals(new ValkeyLua.Result(false, c[1]), lua.eval(c[0]), c[0]);
        }
        // KEYS and ARGV are created after the lock, so their contents stay writable, as on Valkey.
        var r = lua.eval("KEYS[1] = 'z' ARGV[2] = 'w' return KEYS[1] .. ARGV[2]", List.of(b("k")), List.of());
        assertEquals("zw", r.value());
        assertEquals("1", ok("local t = {} t.a = 1 return t.a"));
    }

    @Test
    void failingTostringPoisonsInstance() {
        // Wasm traps (WasiExitException); JNI throws IllegalStateException rather than let luajava abort the JVM.
        assertThrows(RuntimeException.class,
                () -> lua.eval("return setmetatable({}, {__tostring = function() error('no') end})"));
        assertThrows(IllegalStateException.class, () -> lua.eval("return 1"));
    }

    @Test
    void binaryCallArgumentsReachHandlerExactly() {
        var got = new ArrayList<byte[]>();
        var l = create(args -> { got.addAll(args); return "+OK\r\n".getBytes(StandardCharsets.UTF_8); });
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
        var custom = create(args -> "+OK\r\n".getBytes(StandardCharsets.UTF_8),
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
        okOn(create(h), "return redis.call('GET', 'k')");
        assertEquals(List.of("setResp 2", "setRepl 3", "call GET k"), h.ops);
    }

    @Test
    void resetErrorFailsEvalWithoutRunningScript() {
        var h = new RecordingHandler();
        h.setReplReply = "-ERR no replication here\r\n";
        var r = create(h).eval("return redis.call('GET', 'k')");
        assertEquals(false, r.ok());
        assertEquals("ERR no replication here", r.value());
        assertEquals(List.of("setResp 2", "setRepl 3"), h.ops);
    }

    @Test
    void resetRunsEvenAfterFailedEval() {
        var h = new RecordingHandler();
        var l = create(h);
        l.eval("redis.set_repl(redis.REPL_NONE) error('boom')");
        h.ops.clear();
        okOn(l, "return 1");
        assertEquals(List.of("setResp 2", "setRepl 3"), h.ops);
    }

    @Test
    void setresp() {
        var h = new RecordingHandler();
        var l = create(h);
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
        var l = create(h);
        okOn(l, "redis.set_repl(redis.REPL_AOF) return 1");
        assertTrue(h.ops.contains("setRepl 1"), h.ops.toString());
        assertEquals("ERR Invalid replication flags. Use REPL_AOF, REPL_REPLICA, REPL_ALL or REPL_NONE.",
            l.eval("redis.set_repl(8)").value());
        assertEquals("ERR server.set_repl() requires one argument.", l.eval("redis.set_repl()").value());
    }

    @Test
    void log() {
        var h = new RecordingHandler();
        var l = create(h);
        okOn(l, "redis.log(redis.LOG_NOTICE, 'a', 'b', 3) return 1");
        assertTrue(h.ops.contains("log 2 a b 3"), h.ops.toString());
        assertEquals("ERR server.log() requires two arguments or more.", l.eval("redis.log(1)").value());
        assertEquals("ERR First argument must be a number (log level).", l.eval("redis.log('x', 'y')").value());
        assertEquals("ERR Invalid log level.", l.eval("redis.log(9, 'y')").value());
    }

    @Test
    void aclCheckCmd() {
        var h = new RecordingHandler();
        var l = create(h);
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

    /*
     * Valkey runs on 64-bit hosts, where lua_Integer (ptrdiff_t), C long and size_t are 64-bit. lua.wasm is wasm32,
     * patched (lua/patches) so that every value derived from a Lua integer is 64-bit too. These pin 64-bit Valkey's
     * values on both backends; see wasm-difference.md.
     */

    @Test
    void stringFormatIntegersAre64Bit() {
        assertEquals("1099511627776|-2147483649|10000000000|200000000|400000000000|4294967296",
            ok("return string.format('%d|%i|%x|%X|%o|%u', 2^40, -2^31-1, 2^40, 2^33, 2^35, 2^32)"));
    }

    @Test
    void structLongAndSizeTAre64Bit() {
        assertEquals("8 8 8 4", ok("return struct.size('l')..' '..struct.size('L')..' '..struct.size('T')..' '..struct.size('i')"));
        assertEquals("1099511627776", ok("return struct.unpack('>l', struct.pack('>l', 2^40))"));
        assertEquals("1099511627776", ok("return struct.unpack('<i8', struct.pack('<i8', 2^40))"));
    }

    @Test
    void intArgumentsKeepLow32BitsOf64BitInteger() {
        // luaL_checkint is (int)(lua_Integer)d: 2^40 -> 0, 2^31 -> -2^31. wasm32 saturates to INT_MAX instead.
        assertEquals("user_script:1: bad argument #1 to 'random' (interval is empty)", lua.eval("return math.random(2^40)").value());
        assertEquals("true", ok("math.randomseed(2^40) local a = math.random(100) math.randomseed(0) return tostring(a == math.random(100))"));
        assertEquals("0", ok("return select('#', unpack({}, 1, 2^31))"));
    }

    @Test
    void stringIndicesBeyond32BitsAreNotTruncated() {
        assertEquals("|abcdef", ok("return string.sub('abcdef', 2^32 + 2, 2^32 + 3) .. '|' .. string.sub('abcdef', -2^32)"));
        assertEquals("0", ok("return select('#', string.byte('abc', 2^32 + 1))"));
    }

    @Test
    void largeIntegerArgumentsKeepLow32Bits() {
        assertEquals("true", ok("math.randomseed(2^32 + 5) local a = math.random(1000) math.randomseed(5) return tostring(a == math.random(1000))"));
        assertEquals("xxx", ok("return string.rep('x', 2^32 + 3)"));
        assertEquals("A", ok("return string.char(65 + 2^32)"));
        assertEquals("x", ok("local t = {} table.insert(t, 2^32 + 1, 'x') return tostring(t[1])"));
        assertEquals("b", ok("return select(2^32 + 2, 'a', 'b')"));
        assertEquals("user_script:1: Start offset 1 greater than input length 2.",
            lua.eval("return cmsgpack.unpack_one(cmsgpack.pack(1, 2), 2^32 + 1)").value());
    }

    @Test
    void tonumberWithBaseIs64Bit() {
        assertEquals("1099511627775 78364164095", ok("return tonumber('ffffffffff', 16) .. ' ' .. tonumber('zzzzzzz', 36)"));
        // Already matched before the patches (strtod parses hex); guards the coercion path.
        assertEquals("549755813887 1099511627776", ok("return 0x7fffffffff .. ' ' .. ('0x10000000000' + 0)"));
    }

    @Test
    void structUnpackPositionIs64Bit() {
        // Matched before the patches only because wasm32 saturated; a 32-bit size_t pos would truncate 2^32 + 1 to 1.
        assertEquals("user_script:1: bad argument #2 to 'unpack' (data string too short)",
            lua.eval("return struct.unpack('b', 'abc', 2^32 + 1)").value());
        assertEquals("user_script:1: bad argument #2 to 'unpack' (data string too short)",
            lua.eval("return struct.unpack('b', 'abc', -1)").value());
        assertEquals("user_script:1: bad argument #3 to 'unpack' (offset must be 1 or greater)",
            lua.eval("return struct.unpack('b', 'abc', 0)").value());
        assertEquals("98", ok("return struct.unpack('b', 'abc', 2)"));
    }

    @Test
    void cmsgpackUint64AndInt64() {
        assertEquals("-1", ok("return tostring(cmsgpack.unpack('\\207' .. string.rep('\\255', 8)))"));
        // Encoding already matched; guards the path BITS_32=0 switches to lua_tointeger.
        assertEquals("cf0000010000000000 d3ffffff0000000000 -1099511627776", ok(
            "local function hex(b) local h = '' for i=1,#b do h = h .. string.format('%02x', b:byte(i)) end return h end "
            + "return hex(cmsgpack.pack(2^40)) .. ' ' .. hex(cmsgpack.pack(-2^40)) .. ' ' .. tostring(cmsgpack.unpack(cmsgpack.pack(-2^40)))"));
    }

    @Test
    void collectgarbageStepSizeIs64Bit() {
        // lapi.c shifts the step size (KiB) left by 10 into a size_t; 2^22 KiB would wrap to 0 in 32 bits.
        assertEquals("true", ok("collectgarbage() local t = {} for i=1,20000 do t[i] = {} end t = nil return tostring(collectgarbage('step', 2^22))"));
    }

    /*
     * Valkey's own nesting limits (cjson's 1000 levels, LUAI_MAXCCALLS of 200) must fire before any stack runs out:
     * lua.wasm's C stack, or the JVM thread stack Chicory's compiled code runs on. See wasm-difference.md section 7.
     */

    static final String[][] NESTING = {
        {"local t = {} local c = t for i=1,1000 do c[1] = {} c = c[1] end return #cjson.encode(t)",
            "user_script:1: Cannot serialise, excessive nesting (1001)"},
        {"return #cjson.decode(string.rep('[',2000)..string.rep(']',2000))",
            "user_script:1: Found too many nested data structures (1001) at character 1001"},
        {"local function f(n) if n==0 then return 'y' end return (string.gsub('x','x',function() return f(n-1) end)) end return f(250)",
            "C stack overflow"},
    };

    @Test
    void nestingRaisesValkeysErrors() {
        for (String[] c : NESTING) {
            assertEquals(new ValkeyLua.Result(false, c[1]), lua.eval(c[0]), c[0]);
        }
        assertEquals("y", ok("local function f(n) if n==0 then return 'y' end return (string.gsub('x','x',function() return f(n-1) end)) end return f(195)"));
        assertEquals("1", ok("return 1")); // same instance still usable
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
        var vl = create(handler);
        var r = vl.eval("redis.call('SET', KEYS[1], ARGV[1]); return redis.call('GET', KEYS[1])",
                List.of("k".getBytes()), List.of("v".getBytes()));
        assertEquals(new ValkeyLua.Result(true, "v"), r);
        assertEquals(false, vl.eval("return redis.call('NOPE')").ok());
        // Not in the README: the bulk length must be in bytes for non-ASCII values.
        assertEquals(new ValkeyLua.Result(true, "\u00e9"), vl.eval("redis.call('SET', 'k', ARGV[1]); return redis.call('GET', 'k')",
                List.of(), List.of("\u00e9".getBytes(UTF_8))));
    }
}
