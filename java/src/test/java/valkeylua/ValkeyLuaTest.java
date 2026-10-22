package valkeylua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
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

    /** The reply of a script that must succeed, as ISO-8859-1 text. */
    String ok(String script) {
        return okOn(lua, script);
    }

    /** The reply of a script that must fail, as ISO-8859-1 text. */
    String err(String script) {
        return errOn(lua, script);
    }

    static String okOn(ValkeyLua l, String script) {
        var r = l.eval(script);
        assertTrue(r.ok(), () -> "script failed: " + r);
        return text(r);
    }

    static String errOn(ValkeyLua l, String script) {
        var r = l.eval(script);
        assertEquals(false, r.ok(), () -> "script succeeded: " + r);
        return text(r);
    }

    static String text(ValkeyLua.Result r) {
        return new String(r.reply(), ISO_8859_1);
    }

    static ValkeyLua.Result result(boolean ok, String reply) {
        return new ValkeyLua.Result(ok, reply.getBytes(ISO_8859_1));
    }

    static final String NULL = "$-1\r\n";

    static String bulk(String s) {
        return "$" + s.getBytes(ISO_8859_1).length + "\r\n" + s + "\r\n";
    }

    static String integer(long n) {
        return ":" + n + "\r\n";
    }

    static String status(String s) {
        return "+" + s + "\r\n";
    }

    static String error(String s) {
        return "-" + s + "\r\n";
    }

    static String array(String... elems) {
        return "*" + elems.length + "\r\n" + String.join("", elems);
    }

    @Test
    void returnValueTypes() {
        assertEquals(integer(1), ok("return 1"));
        assertEquals(integer(3), ok("return 3.9"));
        assertEquals(integer(-3), ok("return -3.9"));
        assertEquals(bulk("hello"), ok("return 'hello'"));
        assertEquals(integer(1), ok("return true"));
        assertEquals(NULL, ok("return false"));
        assertEquals(NULL, ok("return nil"));
        assertEquals(NULL, ok("return function() end"));
        assertEquals(array(integer(1), bulk("a"), array(integer(2))), ok("return {1, 'a', {2}}"));
        assertEquals(array(integer(1)), ok("return {1, nil, 3}"));
        assertEquals(status("OK"), ok("return redis.call('SET', 'k', 'v')"));
    }

    @Test
    void errorReplies() {
        assertEquals(error("ERR user_script:1: x script: on @user_script:1."), err("error('x')"));
        assertEquals(error("boom"), ok("return {err='boom'}")); // returned, not raised: ok=true
        assertEquals(error("WRONGTYPE bad script: on @user_script:1."), err("error({err='WRONGTYPE bad'})"));
        assertEquals(error("ERR Error compiling script (new function): user_script:1: unexpected symbol near '<eof>'"),
            err("x ="));
        assertEquals(bulk("function"), ok("return type(__server__err__handler)"));
        assertEquals(bulk("true"), ok("return tostring(__redis__err__handler == __server__err__handler)"));
    }

    @Test
    void typedTableReplies() {
        assertEquals(status("a  b"), ok("return {ok='a\\r\\nb'}"));
        assertEquals(status("a"), ok("return {ok='a\\0b'}"));
        assertEquals(error("a  b"), ok("return {err='a\\r\\nb\\r\\n'}"));
        assertEquals(error("a"), ok("return {err='a\\0b'}"));
        assertEquals(array(), ok("return {err=42}")); // not a string: the array case
        assertEquals(bulk("1.5"), ok("return {double=1.5}"));
        assertEquals(bulk("3"), ok("return {double=3}"));
        assertEquals(bulk("nan"), ok("return {double=0/0}"));
        assertEquals(bulk("inf"), ok("return {double=1/0}"));
        assertEquals(bulk("-inf"), ok("return {double=-1/0}"));
        assertEquals(bulk("-0"), ok("return {double=-1/(1/0)}"));
        assertEquals(bulk("0.1"), ok("return {double=0.1}"));
        assertEquals(bulk("123  "), ok("return {big_number='123\\r\\n'}"));
        assertEquals(bulk("a\0b"), ok("return {verbatim_string={format='txt', string='a\\0b'}}"));
        assertEquals(array(bulk("a"), integer(1)), ok("return {map={a=1}}"));
        assertEquals(array(bulk("a")), ok("return {set={a=true}}"));
        assertEquals(array(integer(1), error("e")), ok("return {1, {err='e'}}"));
        assertEquals(bulk("ÿ\0x"), ok("return '\\255\\0x'"));
    }

    @Test
    void raisedErrorFormats() {
        assertEquals(error("42 script: on @user_script:1."), err("error({err=42})"));
        assertEquals(error("ERR unknown error script: on @user_script:1."), err("error({})"));
        assertEquals(error("ERR nil script: on @user_script:1."), err("error(nil)"));
        assertEquals(error("ERR user_script:2: x script: on @user_script:2."),
            err("local function f()\nerror('x')\nend\nf()"));
        // A newline in a compile message becomes a space.
        assertEquals(error("ERR Error compiling script (new function): user_script:2: '<eof>' expected near '[[a b]]'"),
            err("return 1 [[a\nb]]"));
        assertEquals(integer(1), ok("return 1")); // instance still usable
    }

    @Test
    void resp3BooleansAndReset() {
        var l = create(new Handler() {
            public byte[] call(List<byte[]> a) { return "+OK\r\n".getBytes(UTF_8); }
            public byte[] setResp(int v) { return "+OK\r\n".getBytes(UTF_8); }
        });
        assertEquals(integer(0), okOn(l, "redis.setresp(3) return false"));
        assertEquals(integer(1), okOn(l, "redis.setresp(3) return true"));
        assertEquals(NULL, okOn(l, "return false")); // each eval starts at RESP 2
    }

    @Test
    void metatablesDuringConversion() {
        // The conversion reads fields raw, so no metamethod runs on a returned table...
        assertEquals(array(), ok("return setmetatable({}, {__tostring = function() error('no') end})"));
        // ...but error extraction uses lua_getfield, as on Valkey, so __index can supply the fields.
        assertEquals(error("E script: on @user_script:1."),
            err("error(setmetatable({}, {__index = function() return 'E' end}))"));
    }

    @Test
    void raisingIndexOnErrorTablePoisons() {
        // Valkey would hit Lua's panic here; both backends throw instead of letting the error escape unprotected.
        var e = assertThrows(IllegalStateException.class,
            () -> lua.eval("return setmetatable({err='x'}, {__index = function() error('boom') end})"));
        assertEquals("reply conversion failed", e.getMessage());
        assertThrows(IllegalStateException.class, () -> lua.eval("return 1"));
    }

    static final String[] INT64_EDGES = {"return 2^63", "return -2^63-1e5", "return 1/0", "return -1/0", "return 0/0"};

    List<String> int64Edges() {
        return java.util.Arrays.stream(INT64_EDGES).map(this::ok).toList();
    }

    @Test
    void plainLua() {
        assertEquals(bulk("hello 3"), ok("return string.format('hello %d', 1 + 2)"));
    }

    @Test
    void cjson() {
        assertEquals(bulk("{\"a\":1}"), ok("return cjson.encode({a=1})"));
        assertEquals(integer(2), ok("return cjson.decode('[1,2,3]')[2]"));
    }

    @Test
    void cmsgpack() {
        assertEquals(bulk("hi"), ok("return cmsgpack.unpack(cmsgpack.pack({x='hi'})).x"));
    }

    @Test
    void struct() {
        assertEquals(integer(258), ok("return struct.unpack('>H', struct.pack('>H', 258))"));
    }

    @Test
    void bit() {
        assertEquals(integer(4), ok("return bit.band(12, 6)"));
        assertEquals(bulk("ff"), ok("return bit.tohex(255, 2)"));
    }

    @Test
    void luaErrorIsReportedAndInstanceSurvives() {
        var r = lua.eval("error('boom')");
        assertEquals(false, r.ok());
        assertTrue(text(r).contains("boom"), text(r));
        // pcall inside the script exercises setjmp/longjmp without leaving Lua.
        assertEquals(bulk("false"), ok("return tostring(pcall(error, 'x'))"));
        assertEquals(bulk("ok"), ok("return 'ok'"));
    }

    @Test
    void redisCallReachesHostWithArguments() {
        assertEquals(bulk("OK"), ok("return redis.call('SET', 'k', 'v', 'EX', 10).ok"));
        assertEquals(List.of(List.of("SET", "k", "v", "EX", "10")), calls);
    }

    @Test
    void redisCallReplyTypes() {
        reply = ":42\r\n";
        assertEquals(bulk("number 42"), ok("local r = redis.call('INCR', 'n') return type(r) .. ' ' .. r"));
        reply = "$3\r\nbar\r\n";
        assertEquals(bulk("bar"), ok("return redis.call('GET', 'foo')"));
        reply = "$0\r\n\r\n";
        assertEquals(bulk(""), ok("return redis.call('GET', 'foo')"));
        reply = "$-1\r\n";
        assertEquals(NULL, ok("return redis.call('GET', 'missing')"));
        reply = "*-1\r\n";
        assertEquals(NULL, ok("return redis.call('BLPOP', 'q', 1)"));
        reply = "+PONG\r\n";
        assertEquals(bulk("PONG"), ok("return redis.call('PING').ok"));
        reply = "*3\r\n$1\r\na\r\n:2\r\n*1\r\n$-1\r\n";
        assertEquals(bulk("3 a 2 false"), ok(
                "local r = redis.call('X') return #r .. ' ' .. r[1] .. ' ' .. r[2] .. ' ' .. tostring(r[3][1])"));
    }

    @Test
    void redisCallRaisesErrorReplyAndPcallReturnsIt() {
        reply = "-WRONGTYPE bad\r\n";
        assertEquals(error("WRONGTYPE bad script: on @user_script:1."), err("redis.call('INCR', 'k') return 'not reached'"));
        assertEquals(bulk("WRONGTYPE bad"), ok("return redis.pcall('INCR', 'k').err"));
        assertEquals(bulk("false WRONGTYPE bad"), ok(
                "local ok, e = pcall(redis.call, 'INCR', 'k') return tostring(ok) .. ' ' .. e"));
    }

    @Test
    void redisIsAliasOfServer() {
        assertEquals(bulk("true"), ok("return tostring(redis == server)"));
        assertEquals(bulk("OK"), ok("return server.call('SET', 'k', 'v').ok"));
    }

    @Test
    void commandErrorRewrites() {
        reply = "-ERR unknown command 'FOO', with args beginning with: \r\n";
        assertEquals(bulk("ERR Unknown command called from script"), ok("return redis.pcall('FOO').err"));
        reply = "-ERR wrong number of arguments for 'get' command\r\n";
        assertEquals(bulk("ERR Wrong number of args calling command from script"), ok("return redis.pcall('GET').err"));
        reply = "-ERR command not allowed\r\n";
        assertEquals(bulk("ERR This Valkey command is not allowed from script"), ok("return redis.pcall('X').err"));
        reply = "-NOPERM no access to key\r\n";
        assertEquals(bulk("ERR ACL failure in script: no access to key"), ok("return redis.pcall('GET', 'k').err"));
        reply = "-WRONGTYPE bad\r\n";
        assertEquals(bulk("WRONGTYPE bad"), ok("return redis.pcall('GET', 'k').err"));
        assertEquals(bulk("true"), ok("return tostring(redis.pcall('GET', 'k').ignore_error_stats_update)"));
    }

    @Test
    void nestedErrorFailsWholeCall() {
        reply = "*2\r\n+OK\r\n-ERR inner\r\n";
        assertEquals(bulk("OK ERR inner true"), ok(
            "local r = redis.pcall('EXEC') return r[1].ok .. ' ' .. r[2].err .. ' ' .. tostring(r[2].ignore_error_stats_update)"));
        assertEquals(bulk("OK ERR inner"), ok(
            "local ok, e = pcall(function() return redis.call('EXEC') end) return e[1].ok .. ' ' .. e[2].err"));
    }

    @Test
    void globalPcallReturnsErrorStrings() {
        reply = "-WRONGTYPE bad\r\n";
        assertEquals(bulk("false WRONGTYPE bad"), ok(
            "local ok, e = pcall(redis.call, 'INCR', 'k') return tostring(ok) .. ' ' .. e"));
        assertEquals(bulk("true 1 2"), ok("local ok, a, b = pcall(function() return 1, 2 end) return tostring(ok)..' '..a..' '..b"));
    }

    @Test
    void malformedReplyIsScriptError() {
        for (String bad : List.of("", "hello", "$5\r\nab", "*2\r\n:1\r\n", "+OK\r\ngarbage", "+OK\r\n+OK\r\n",
                ":9223372036854775808\r\n", ":-9223372036854775809\r\n", "*1\r\n".repeat(10000) + ":1\r\n")) {
            reply = bad;
            for (String fn : List.of("call", "pcall")) {
                assertEquals(error("ERR unexpected reply from handler for server.call script: on @user_script:1."),
                    err("return redis." + fn + "('GET', 'k')"), bad);
            }
        }
        reply = "+OK\r\n";
        assertEquals(bulk("OK"), ok("return redis.call('PING').ok"));
    }

    @Test
    void fullLongLongRangeIntegerReplies() {
        reply = ":9223372036854775807\r\n";
        assertEquals(bulk("true"), ok("return tostring(redis.call('X') > 9e18)"));
        reply = ":-9223372036854775808\r\n";
        assertEquals(bulk("true"), ok("return tostring(redis.call('X') < -9e18)"));
    }

    @Test
    void nestedReplyUpToDepthLimitConverts() {
        reply = "*1\r\n".repeat(200) + ":1\r\n";
        assertEquals(integer(1), ok("local r = redis.call('X') for i = 1, 200 do r = r[1] end return r"));
    }

    @Test
    void bulkReplyWithCrlfIsBinarySafe() {
        reply = "$5\r\na\r\n\0b\r\n";
        assertEquals(integer(5), ok("return #redis.call('GET', 'k')"));
    }

    @Test
    void largeArrayReply() {
        var sb = new StringBuilder("*10000\r\n");
        for (int i = 0; i < 10000; i++) sb.append(":").append(i).append("\r\n");
        reply = sb.toString();
        assertEquals(bulk("10000 9999"), ok("local r = redis.call('X') return #r .. ' ' .. r[10000]"));
    }

    @Test
    void numberArgumentsFormatLikeValkey() {
        ok("return redis.call('X', 10, -5, 3.0, 0.1, 2.5)");
        assertEquals(List.of("X", "10", "-5", "3", "0.1", "2.5"), calls.get(0));
    }

    @Test
    void argumentErrorMessagesMatchValkey() {
        assertEquals(error("ERR Please specify at least one argument for this call script: on @user_script:1."), err("return redis.call()"));
        assertEquals(error("ERR Command arguments must be strings or integers script: on @user_script:1."), err("return redis.call('SET', {})"));
        assertEquals(bulk("ERR Command arguments must be strings or integers"), ok("return redis.pcall('SET', {}).err"));
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
        assertEquals(error("ERR mapping broke script: on @user_script:1."), errOn(l, "return redis.call('GET', 'k')"));
        assertEquals(bulk("ERR mapping broke"), okOn(l, "return redis.pcall('GET', 'k').err"));
    }

    @Test
    void exceptionMessageWithNewlinesStaysOneError() {
        var l = create(args -> { throw new RuntimeException("a\r\nb"); });
        assertEquals(bulk("ERR a  b"), okOn(l, "return redis.pcall('GET', 'k').err"));
    }

    @Test
    void exceptionWithoutMessageUsesClassName() {
        var l = create(args -> { throw new NullPointerException(); });
        assertEquals(bulk("ERR NullPointerException"), okOn(l, "return redis.pcall('GET', 'k').err"));
    }

    @Test
    void handlerReturningNullIsScriptError() {
        var l = create(args -> null);
        for (int i = 0; i < 100; i++) {
            assertEquals(bulk("ERR handler returned null"), okOn(l, "return redis.pcall('GET', 'k').err"));
        }
        assertEquals(integer(2), okOn(l, "return 1 + 1"));
    }

    @Test
    void handlerErrorBecomesScriptError() {
        var l = create(args -> { throw new StackOverflowError(); });
        assertEquals(bulk("ERR StackOverflowError"), okOn(l, "return redis.pcall('GET', 'k').err"));
        var l2 = create(args -> { throw new AssertionError("bad"); });
        assertEquals(bulk("ERR bad"), okOn(l2, "return redis.pcall('GET', 'k').err"));
    }

    @Test
    void escapedExceptionPoisonsInstance() {
        assertThrows(NullPointerException.class, () -> lua.eval("return 1", (byte[]) null));
        var e = assertThrows(IllegalStateException.class, () -> lua.eval("return 1"));
        assertEquals("ValkeyLua instance is unusable after an escaped exception; create a new one", e.getMessage());
    }

    @Test
    void luajavaBridgeIsNotReachableFromScripts() {
        assertEquals(bulk("nil nil"), ok("return tostring(rawget(_G, 'java')) .. ' ' .. tostring(rawget(_G, 'debug'))"));
    }

    @Test
    void globalsAreValkeysAllowList() {
        assertEquals(bulk("ARGV KEYS _G _VERSION __redis__err__handler __server__err__handler assert bit cjson cmsgpack "
                + "collectgarbage coroutine error gcinfo getmetatable ipairs load loadstring math next os pairs pcall "
                + "rawequal rawget rawset redis select server setmetatable string struct table tonumber tostring type "
                + "unpack xpcall"),
            // Sorted in Java: Lua compares strings with strcoll, which follows the JVM process's locale on JNI.
            bulk(String.join(" ", java.util.Arrays.stream(
                ok("local t = {} for k in pairs(_G) do t[#t + 1] = k end return table.concat(t, ' ')").split("\r\n")[1]
                    .split(" ")).sorted().toList())));
    }

    @Test
    void globalsAreProtected() {
        for (String[] c : new String[][] {
                {"x = 1", error("ERR user_script:1: Attempt to modify a readonly table script: on @user_script:1.")},
                {"rawset(_G, 'x', 1)", error("ERR Attempt to modify a readonly table script: on @user_script:1.")}, // raised in C: no position in the message
                {"function f() end", error("ERR user_script:1: Attempt to modify a readonly table script: on @user_script:1.")},
                {"string.x = 1", error("ERR user_script:1: Attempt to modify a readonly table script: on @user_script:1.")},
                {"getmetatable('').__index = {}", error("ERR user_script:1: Attempt to modify a readonly table script: on @user_script:1.")},
                {"setmetatable(_G, nil)", error("ERR Attempt to modify a readonly table script: on @user_script:1.")},
                {"return y", error("ERR user_script:1: Script attempted to access nonexistent global variable 'y' script: on @user_script:1.")},
                {"return print", error("ERR user_script:1: Script attempted to access nonexistent global variable 'print' script: on @user_script:1.")},
                {"return debug", error("ERR user_script:1: Script attempted to access nonexistent global variable 'debug' script: on @user_script:1.")}}) {
            assertEquals(result(false, c[1]), lua.eval(c[0]), c[0]);
        }
        // KEYS and ARGV are created after the lock, so their contents stay writable, as on Valkey.
        var r = lua.eval("KEYS[1] = 'z' ARGV[2] = 'w' return KEYS[1] .. ARGV[2]", List.of(b("k")), List.of());
        assertEquals(bulk("zw"), text(r));
        assertEquals(integer(1), ok("local t = {} t.a = 1 return t.a"));
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
        assertEquals(bulk("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d"), ok("return redis.sha1hex('hello')"));
        assertEquals(bulk("da39a3ee5e6b4b0d3255bfef95601890afd80709"), ok("return redis.sha1hex('')"));
        assertEquals(error("ERR wrong number of arguments script: on @user_script:1."), err("return redis.sha1hex()"));
    }

    @Test
    void errorAndStatusReply() {
        assertEquals(bulk("ERR x"), ok("return redis.error_reply('ERR x').err"));
        assertEquals(bulk("ERR x"), ok("return redis.error_reply('-ERR x').err"));
        assertEquals(bulk("FINE"), ok("return redis.status_reply('FINE').ok"));
        // Valkey quirk: bad arguments return an error table instead of raising.
        assertEquals(bulk("ERR wrong number or type of arguments"), ok("return redis.error_reply(1).err"));
        assertEquals(bulk("ERR wrong number or type of arguments"), ok("return redis.status_reply().err"));
    }

    @Test
    void debuggerStubsAndReplicateCommands() {
        assertEquals(bulk("false"), ok("return tostring(redis.breakpoint())"));
        assertEquals(integer(0), ok("return select('#', redis.debug('x', 1))"));
        assertEquals(bulk("true"), ok("return tostring(redis.replicate_commands())"));
    }

    @Test
    void constants() {
        assertEquals(bulk("0 1 2 2 3"), ok("return redis.REPL_NONE..' '..redis.REPL_AOF..' '..redis.REPL_SLAVE..' '..redis.REPL_REPLICA..' '..redis.REPL_ALL"));
        assertEquals(bulk("0 1 2 3"), ok("return redis.LOG_DEBUG..' '..redis.LOG_VERBOSE..' '..redis.LOG_NOTICE..' '..redis.LOG_WARNING"));
        assertEquals(bulk("7.2.4 459268 255.255.255 16777215 valkey"), ok(
            "return redis.REDIS_VERSION..' '..redis.REDIS_VERSION_NUM..' '..redis.VALKEY_VERSION..' '..redis.VALKEY_VERSION_NUM..' '..redis.SERVER_NAME"));
        var custom = create(args -> "+OK\r\n".getBytes(StandardCharsets.UTF_8),
                new ServerInfo("7.0.1", "8.1.0", "acme"));
        assertEquals(bulk("7.0.1 458753 8.1.0 524544 acme"), okOn(custom,
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
        assertEquals(error("ERR no replication here"), errOn(create(h), "return redis.call('GET', 'k')"));
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
        assertEquals(integer(0), okOn(l, "return select('#', redis.setresp(2))"));
        assertEquals(error("ERR RESP3 is not supported script: on @user_script:1."), errOn(l, "redis.setresp(3)"));
        assertEquals(error("ERR RESP version must be 2 or 3. script: on @user_script:1."), errOn(l, "redis.setresp(4)"));
        assertEquals(error("ERR server.setresp() requires one argument. script: on @user_script:1."), errOn(l, "redis.setresp()"));
        h.setRespReply = ":1\r\n"; // now the per-eval reset itself fails, with the same message
        assertEquals(error("ERR unexpected reply from handler for server.setresp"), errOn(l, "redis.setresp(2)"));
    }

    @Test
    void setRepl() {
        var h = new RecordingHandler();
        var l = create(h);
        okOn(l, "redis.set_repl(redis.REPL_AOF) return 1");
        assertTrue(h.ops.contains("setRepl 1"), h.ops.toString());
        assertEquals(error("ERR Invalid replication flags. Use REPL_AOF, REPL_REPLICA, REPL_ALL or REPL_NONE. script: on @user_script:1."),
            errOn(l, "redis.set_repl(8)"));
        assertEquals(error("ERR server.set_repl() requires one argument. script: on @user_script:1."), errOn(l, "redis.set_repl()"));
    }

    @Test
    void log() {
        var h = new RecordingHandler();
        var l = create(h);
        okOn(l, "redis.log(redis.LOG_NOTICE, 'a', 'b', 3) return 1");
        assertTrue(h.ops.contains("log 2 a b 3"), h.ops.toString());
        assertEquals(error("ERR server.log() requires two arguments or more. script: on @user_script:1."), errOn(l, "redis.log(1)"));
        assertEquals(error("ERR First argument must be a number (log level). script: on @user_script:1."), errOn(l, "redis.log('x', 'y')"));
        assertEquals(error("ERR Invalid log level. script: on @user_script:1."), errOn(l, "redis.log(9, 'y')"));
    }

    @Test
    void aclCheckCmd() {
        var h = new RecordingHandler();
        var l = create(h);
        assertEquals(bulk("true"), okOn(l, "return tostring(redis.acl_check_cmd('GET', 'k'))"));
        h.aclReply = ":0\r\n";
        assertEquals(bulk("false"), okOn(l, "return tostring(redis.acl_check_cmd('GET', 'k'))"));
        h.aclReply = "-ERR Invalid command passed to server.acl_check_cmd()\r\n";
        assertEquals(error("ERR Invalid command passed to server.acl_check_cmd() script: on @user_script:1."), errOn(l, "redis.acl_check_cmd('NOPE')"));
        h.aclReply = "+OK\r\n";
        assertEquals(error("ERR unexpected reply from handler for server.acl_check_cmd script: on @user_script:1."), errOn(l, "redis.acl_check_cmd('GET')"));
        assertEquals(error("ERR Please specify at least one argument for this call script: on @user_script:1."), errOn(l, "redis.acl_check_cmd()"));
    }

    @Test
    void mathRandomMatchesValkey() {
        assertEquals(bulk("75 13"), ok("math.randomseed(42) return math.random(100) .. ' ' .. math.random(10, 20)"));
        // luaL_error from a C function: luaL_where(1) prefixes the calling script's position; no ERR prefix.
        assertEquals(error("ERR user_script:1: wrong number of arguments script: on @user_script:1."), err("return math.random(1, 2, 3)"));
    }

    /*
     * Valkey runs on 64-bit hosts, where lua_Integer (ptrdiff_t), C long and size_t are 64-bit. lua.wasm is wasm32,
     * patched (lua/patches) so that every value derived from a Lua integer is 64-bit too. These pin 64-bit Valkey's
     * values on both backends; see gaps.md.
     */

    @Test
    void stringFormatIntegersAre64Bit() {
        assertEquals(bulk("1099511627776|-2147483649|10000000000|200000000|400000000000|4294967296"),
            ok("return string.format('%d|%i|%x|%X|%o|%u', 2^40, -2^31-1, 2^40, 2^33, 2^35, 2^32)"));
    }

    @Test
    void structLongAndSizeTAre64Bit() {
        assertEquals(bulk("8 8 8 4"), ok("return struct.size('l')..' '..struct.size('L')..' '..struct.size('T')..' '..struct.size('i')"));
        assertEquals(integer(1099511627776L), ok("return struct.unpack('>l', struct.pack('>l', 2^40))"));
        assertEquals(integer(1099511627776L), ok("return struct.unpack('<i8', struct.pack('<i8', 2^40))"));
    }

    @Test
    void intArgumentsKeepLow32BitsOf64BitInteger() {
        // luaL_checkint is (int)(lua_Integer)d: 2^40 -> 0, 2^31 -> -2^31. wasm32 saturates to INT_MAX instead.
        assertEquals(error("ERR user_script:1: bad argument #1 to 'random' (interval is empty) script: on @user_script:1."), err("return math.random(2^40)"));
        assertEquals(bulk("true"), ok("math.randomseed(2^40) local a = math.random(100) math.randomseed(0) return tostring(a == math.random(100))"));
        assertEquals(integer(0), ok("return select('#', unpack({}, 1, 2^31))"));
    }

    @Test
    void stringIndicesBeyond32BitsAreNotTruncated() {
        assertEquals(bulk("|abcdef"), ok("return string.sub('abcdef', 2^32 + 2, 2^32 + 3) .. '|' .. string.sub('abcdef', -2^32)"));
        assertEquals(integer(0), ok("return select('#', string.byte('abc', 2^32 + 1))"));
    }

    @Test
    void largeIntegerArgumentsKeepLow32Bits() {
        assertEquals(bulk("true"), ok("math.randomseed(2^32 + 5) local a = math.random(1000) math.randomseed(5) return tostring(a == math.random(1000))"));
        assertEquals(bulk("xxx"), ok("return string.rep('x', 2^32 + 3)"));
        assertEquals(bulk("A"), ok("return string.char(65 + 2^32)"));
        assertEquals(bulk("x"), ok("local t = {} table.insert(t, 2^32 + 1, 'x') return tostring(t[1])"));
        assertEquals(bulk("b"), ok("return select(2^32 + 2, 'a', 'b')"));
        assertEquals(error("ERR user_script:1: Start offset 1 greater than input length 2. script: on @user_script:1."),
            err("return cmsgpack.unpack_one(cmsgpack.pack(1, 2), 2^32 + 1)"));
    }

    @Test
    void tonumberWithBaseIs64Bit() {
        assertEquals(bulk("1099511627775 78364164095"), ok("return tonumber('ffffffffff', 16) .. ' ' .. tonumber('zzzzzzz', 36)"));
        // Already matched before the patches (strtod parses hex); guards the coercion path.
        assertEquals(bulk("549755813887 1099511627776"), ok("return 0x7fffffffff .. ' ' .. ('0x10000000000' + 0)"));
    }

    @Test
    void structUnpackPositionIs64Bit() {
        // Matched before the patches only because wasm32 saturated; a 32-bit size_t pos would truncate 2^32 + 1 to 1.
        assertEquals(error("ERR user_script:1: bad argument #2 to 'unpack' (data string too short) script: on @user_script:1."),
            err("return struct.unpack('b', 'abc', 2^32 + 1)"));
        assertEquals(error("ERR user_script:1: bad argument #2 to 'unpack' (data string too short) script: on @user_script:1."),
            err("return struct.unpack('b', 'abc', -1)"));
        assertEquals(error("ERR user_script:1: bad argument #3 to 'unpack' (offset must be 1 or greater) script: on @user_script:1."),
            err("return struct.unpack('b', 'abc', 0)"));
        assertEquals(integer(98), ok("return struct.unpack('b', 'abc', 2)"));
    }

    @Test
    void cmsgpackUint64AndInt64() {
        assertEquals(bulk("-1"), ok("return tostring(cmsgpack.unpack('\\207' .. string.rep('\\255', 8)))"));
        // Encoding already matched; guards the path BITS_32=0 switches to lua_tointeger.
        assertEquals(bulk("cf0000010000000000 d3ffffff0000000000 -1099511627776"), ok(
            "local function hex(b) local h = '' for i=1,#b do h = h .. string.format('%02x', b:byte(i)) end return h end "
            + "return hex(cmsgpack.pack(2^40)) .. ' ' .. hex(cmsgpack.pack(-2^40)) .. ' ' .. tostring(cmsgpack.unpack(cmsgpack.pack(-2^40)))"));
    }

    @Test
    void collectgarbageStepSizeIs64Bit() {
        // lapi.c shifts the step size (KiB) left by 10 into a size_t; 2^22 KiB would wrap to 0 in 32 bits.
        assertEquals(bulk("true"), ok("collectgarbage() local t = {} for i=1,20000 do t[i] = {} end t = nil return tostring(collectgarbage('step', 2^22))"));
    }

    /*
     * Valkey's own nesting limits (cjson's 1000 levels, LUAI_MAXCCALLS of 200) must fire before any stack runs out:
     * lua.wasm's C stack, or the JVM thread stack Chicory's compiled code runs on. See gaps.md section 4.
     */

    static final String[][] NESTING = {
        {"local t = {} local c = t for i=1,1000 do c[1] = {} c = c[1] end return #cjson.encode(t)",
            "-ERR user_script:1: Cannot serialise, excessive nesting (1001) script: on @user_script:1.\r\n"},
        {"return #cjson.decode(string.rep('[',2000)..string.rep(']',2000))",
            "-ERR user_script:1: Found too many nested data structures (1001) at character 1001 script: on @user_script:1.\r\n"},
        {"local function f(n) if n==0 then return 'y' end return (string.gsub('x','x',function() return f(n-1) end)) end return f(250)",
            "-ERR C stack overflow script: on @user_script:1.\r\n"},
    };

    @Test
    void nestingRaisesValkeysErrors() {
        for (String[] c : NESTING) {
            assertEquals(result(false, c[1]), lua.eval(c[0]), c[0]);
        }
        assertEquals(bulk("y"), ok("local function f(n) if n==0 then return 'y' end return (string.gsub('x','x',function() return f(n-1) end)) end return f(195)"));
        assertEquals(integer(1), ok("return 1")); // same instance still usable
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void keysAndArgv() {
        var r = lua.eval("return #KEYS .. ' ' .. KEYS[1] .. ' ' .. #ARGV .. ' ' .. ARGV[2]",
            List.of(b("k1")), List.of(b("a1"), b("a2")));
        assertEquals(bulk("1 k1 2 a2"), text(r));
        assertEquals(bulk("0 0"), ok("return #KEYS .. ' ' .. #ARGV"));
    }

    @Test
    void keysAndArgvAreBinarySafe() {
        var r = lua.eval("return #KEYS[1] .. ' ' .. #ARGV[1]", List.of(b("a\0b")), List.of(b("x\r\ny")));
        assertEquals(bulk("3 4"), text(r));
    }

    @Test
    void malformedKeysArgvBufferIsAnError() {
        for (String bad : new String[] {"", "*3\r\n", "*2\r\n*1\r\n$5\r\nab\r\n*0\r\n"}) {
            var r = lua.eval("return 1", bad.getBytes(StandardCharsets.UTF_8));
            assertEquals(result(false, error("ERR malformed KEYS/ARGV buffer")), r);
        }
        assertEquals(integer(2), ok("return 1 + 1"));
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
        assertEquals(result(true, bulk("v")), r);
        assertEquals(false, vl.eval("return redis.call('NOPE')").ok());
        // Not in the README: the bulk length must be in bytes for non-ASCII values.
        assertEquals(new ValkeyLua.Result(true, "$2\r\n\u00e9\r\n".getBytes(UTF_8)), vl.eval("redis.call('SET', 'k', ARGV[1]); return redis.call('GET', 'k')",
                List.of(), List.of("\u00e9".getBytes(UTF_8))));
    }
}
