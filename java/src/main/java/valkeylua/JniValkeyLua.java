package valkeylua;

import static party.iroiro.luajava.lua51.Lua51Consts.LUA_MULTRET;
import static party.iroiro.luajava.lua51.Lua51Consts.LUA_REGISTRYINDEX;
import static party.iroiro.luajava.lua51.Lua51Consts.LUA_TNUMBER;
import static party.iroiro.luajava.lua51.Lua51Consts.LUA_TSTRING;
import static party.iroiro.luajava.lua51.Lua51Consts.LUA_TTABLE;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import party.iroiro.luajava.JFunction;
import party.iroiro.luajava.Lua;
import party.iroiro.luajava.lua51.Lua51;
import party.iroiro.luajava.lua51.Lua51Natives;

/**
 * Runs scripts on Valkey's bundled Lua through luajava (JNI), as Infinispan does. The server.* API is a Java port
 * of {@link WasmValkeyLua}'s valkey_lua.c, written against the raw Lua C API so the two read side by side. Lua
 * strings are bytes; where the C code uses C strings this code uses ISO-8859-1 Java strings, which map bytes 1:1.
 * Not thread-safe.
 */
public final class JniValkeyLua extends ValkeyLua {

    // Loads the natives (liblua51*, built by native/Makefile) once, as lua.wasm was compiled once per instance.
    private static final Lua51Natives C;

    static {
        var lua = new Lua51();
        C = (Lua51Natives) lua.getLuaNatives();
        lua.close();
    }

    // Added to luajava's natives by native/valkey_jni.c.
    private static native void openValkeyLibs(long L);

    private static native void pushlstring(long L, byte[] s, int off, int len);

    private static native byte[] fpconvDtoa(double d);

    private static native void lockGlobals(long L);

    private static native void enableReadonlyGlobals(long L, boolean enabled);

    // EVAL's error handler and reply (lua/valkey_reply.c); kinds as its enum.
    private static native void installErrorHandler(long L);

    private static native void pushErrorHandler(long L);

    private static native void setScriptResp(long L, int resp);

    /** Consumes the top of the stack; null if the conversion raised. */
    private static native byte[] evalReply(long L, int kind);

    /** The bytecode, or (starting with '-') EVAL's compile error reply. */
    private static native byte[] compileScript(byte[] src);

    private static native int loadCompiled(long L, byte[] bytecode);

    // Lua's C code recurses on the native stack of the thread running it, and overflowing that kills the JVM. Evals
    // run on these threads with Valkey's 8 MiB (its main thread's default), so Valkey's own nesting limits fire first
    // whatever the caller's stack (1 MiB on Linux x86_64, where str_gsub's 8 KiB luaL_Buffer outruns it at about 110
    // nested callbacks; see gaps.md section 4). Idle threads exit after a minute.
    private static final long LUA_STACK_SIZE = 8L << 20;
    private static final ExecutorService LUA_THREADS = Executors.newCachedThreadPool(r -> {
        var t = new Thread(null, r, "valkey-lua", LUA_STACK_SIZE);
        t.setDaemon(true);
        return t;
    });

    private static final int EVAL_OK = 0, EVAL_SETUP = 1, EVAL_COMPILE = 2, EVAL_RUN = 3;

    private static final int MAX_NESTED_COLLECTIONS_DEPTH = 256;
    private static final byte[] OK = "+OK\r\n".getBytes(StandardCharsets.US_ASCII);

    private final Handler handler;
    private final ServerInfo info;
    // Valkey's rand.c state: drand48's LCG. Like the static in lua.wasm, it lives as long as this instance.
    private long rand48 = 0x1234ABCD330EL;

    public JniValkeyLua(Handler handler) {
        this(handler, ServerInfo.DEFAULT);
    }

    public JniValkeyLua(Handler handler, ServerInfo info) {
        this.handler = handler;
        this.info = info;
    }

    /* ---------------------------------------------------------------------------------------------------------
     * Errors and replies
     * ------------------------------------------------------------------------------------------------------- */

    /** luaPushErrorBuff: pushes {err="CODE msg"}; a leading '-' is stripped, a bare message gets an ERR code. */
    private static void luaPushError(long L, String err) {
        String finalMsg;
        if (err.startsWith("-")) {
            int sp = err.indexOf(' ');
            finalMsg = sp < 0 ? "ERR " + err.substring(1) : err.substring(1, sp) + " " + trimCrlf(err.substring(sp + 1));
        } else {
            finalMsg = "ERR " + trimCrlf(err);
        }
        C.lua_newtable(L);
        pushString(L, finalMsg);
        C.lua_setfield(L, -2, "err");
    }

    /** luaProcessReplyError; the rewrites match on the message prefix only, as in the Wasm variant. */
    private static void luaProcessReplyError(long L, String err) {
        if (err.startsWith("ERR command ")) {
            luaPushError(L, "This Valkey command is not allowed from script");
        } else if (err.startsWith("ERR wrong number of arguments for ")) {
            luaPushError(L, "Wrong number of args calling command from script");
        } else if (err.startsWith("ERR unknown command '")) {
            luaPushError(L, "Unknown command called from script");
        } else if (err.startsWith("NOPERM ")) {
            luaPushError(L, "ACL failure in script: " + err.substring("NOPERM ".length()));
        } else {
            luaPushError(L, "-" + err);
        }
        C.lua_pushboolean(L, 1);
        C.lua_setfield(L, -2, "ignore_error_stats_update");
    }

    /**
     * Pushes one RESP2 reply as Lua, using Valkey's RESP2 -> Lua mapping. Returns the position after the reply, or
     * -1 if it is malformed, truncated or nested deeper than MAX_NESTED_COLLECTIONS_DEPTH. Sets error[0] if an
     * error reply appears at any depth.
     */
    private static int pushReply(long L, byte[] b, int p, boolean[] error, int depth) {
        int end = b.length;
        if (p >= end || depth > MAX_NESTED_COLLECTIONS_DEPTH || C.lua_checkstack(L, 3) == 0) return -1;
        byte type = b[p++];
        int line = p, eol = -1;
        for (int q = p; q + 1 < end; q++) {
            if (b[q] == '\r' && b[q + 1] == '\n') {
                eol = q;
                break;
            }
        }
        if (eol < 0) return -1;
        p = eol + 2;
        Long n;
        switch (type) {
            case '+':
                C.lua_newtable(L);
                pushlstring(L, b, line, eol - line);
                C.lua_setfield(L, -2, "ok");
                return p;
            case '-':
                luaProcessReplyError(L, new String(b, line, eol - line, StandardCharsets.ISO_8859_1));
                error[0] = true;
                return p;
            case ':':
                if ((n = parseInt(b, line, eol)) == null) return -1;
                C.lua_pushnumber(L, n);
                return p;
            case '$':
                if ((n = parseInt(b, line, eol)) == null || n < -1) return -1;
                if (n < 0) {
                    C.lua_pushboolean(L, 0);
                    return p;
                }
                if (n > end - p || end - p - n < 2 || b[p + n.intValue()] != '\r' || b[p + n.intValue() + 1] != '\n') return -1;
                pushlstring(L, b, p, n.intValue());
                return p + n.intValue() + 2;
            case '*':
                if ((n = parseInt(b, line, eol)) == null || n < -1) return -1;
                if (n < 0) {
                    C.lua_pushboolean(L, 0);
                    return p;
                }
                if (n > end - p) return -1; // every element takes at least one byte
                C.lua_newtable(L);
                for (int i = 1; i <= n; i++) {
                    p = pushReply(L, b, p, error, depth + 1);
                    if (p < 0) return -1;
                    C.lua_rawseti(L, -2, i);
                }
                return p;
            default:
                return -1;
        }
    }

    /** The integer in b[p, end), or null if it is not one (strtoll without its whitespace and '+'). */
    private static Long parseInt(byte[] b, int p, int end) {
        if (p == end || (b[p] != '-' && (b[p] < '0' || b[p] > '9'))) return null;
        try {
            return Long.parseLong(new String(b, p, end - p, StandardCharsets.ISO_8859_1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Port of luaArgsToServerArgv: the call arguments, with numbers formatted as Valkey does. On failure pushes the
     * Valkey error table and returns null.
     */
    private static List<byte[]> luaArgsToServerArgv(long L) {
        int argc = C.lua_gettop(L);
        if (argc == 0) {
            luaPushError(L, "Please specify at least one argument for this call");
            return null;
        }
        var args = new ArrayList<byte[]>(argc);
        for (int j = 1; j <= argc; j++) {
            if (C.lua_type(L, j) == LUA_TNUMBER) {
                // Integers print as integers (double2ll + ll2string), everything else via fpconv_dtoa.
                double num = C.lua_tonumber(L, j);
                long lvalue = (long) num;
                if (!(num < (double) (-Long.MAX_VALUE / 2) || num > (double) (Long.MAX_VALUE / 2)) && lvalue == num) {
                    args.add(Long.toString(lvalue).getBytes(StandardCharsets.US_ASCII));
                } else {
                    args.add(fpconvDtoa(num));
                }
            } else {
                byte[] s = toBytes(L, j);
                if (s == null) {
                    luaPushError(L, "Command arguments must be strings or integers");
                    return null;
                }
                args.add(s);
            }
        }
        return args;
    }


    /* ---------------------------------------------------------------------------------------------------------
     * server.* functions. A JFunction returning -1 raises the value on top of the stack as a Lua error.
     * ------------------------------------------------------------------------------------------------------- */

    private int redisGenericCommand(Lua l, boolean raiseError) {
        long L = l.getPointer();
        List<byte[]> args = luaArgsToServerArgv(L);
        if (args == null) return raiseError ? -1 : 1;
        byte[] reply = host(() -> handler.call(args));
        boolean[] error = {false};
        // Trailing bytes after the reply make it malformed too.
        if (reply.length == 0 || pushReply(L, reply, 0, error, 0) != reply.length) {
            luaPushError(L, "unexpected reply from handler for server.call");
            return -1;
        }
        return error[0] && raiseError ? -1 : 1;
    }

    /** Port of luaRedisPcall: server.pcall-style error tables are turned into their message string. */
    private static int luaRedisPcall(Lua l) {
        long L = l.getPointer();
        int argc = C.lua_gettop(L);
        C.lua_pushboolean(L, 1); // result place holder
        C.lua_insert(L, 1);
        if (C.lua_pcall(L, argc - 1, LUA_MULTRET, 0) != 0) {
            C.lua_remove(L, 1); // remove the result place holder, now we have room for at least one element
            if (C.lua_type(L, -1) == LUA_TTABLE) {
                C.lua_getfield(L, -1, "err");
                if (C.lua_isstring(L, -1) != 0) {
                    C.lua_replace(L, -2); // replace the error message with the table
                }
            }
            C.lua_pushboolean(L, 0); // push result
            C.lua_insert(L, 1);
        }
        return C.lua_gettop(L);
    }

    private static int luaRedisSha1hexCommand(Lua l) {
        long L = l.getPointer();
        if (C.lua_gettop(L) != 1) {
            luaPushError(L, "wrong number of arguments");
            return -1;
        }
        byte[] s = toBytes(L, 1);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(s == null ? new byte[0] : s);
            C.lua_pushstring(L, HexFormat.of().formatHex(hash));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        return 1;
    }

    private static int luaRedisReturnSingleFieldTable(Lua l, String field) {
        long L = l.getPointer();
        if (C.lua_gettop(L) != 1 || C.lua_type(L, -1) != LUA_TSTRING) {
            luaPushError(L, "wrong number or type of arguments");
            return 1;
        }
        C.lua_newtable(L);
        C.lua_pushvalue(L, -2);
        C.lua_setfield(L, -2, field);
        return 1;
    }

    private static int luaRedisErrorReplyCommand(Lua l) {
        long L = l.getPointer();
        if (C.lua_gettop(L) != 1 || C.lua_type(L, -1) != LUA_TSTRING) {
            luaPushError(L, "wrong number or type of arguments");
            return 1;
        }
        // add '-' if not exists
        String err = latin1(toBytes(L, -1));
        luaPushError(L, err.startsWith("-") ? err : "-" + err);
        return 1;
    }

    /**
     * Validates a non-call reply. An error reply is pushed as a Lua error table; with want '+' the reply must be
     * exactly +OK, with ':' exactly :0 or :1 (returned). Anything else pushes "unexpected reply". Returns -1 after
     * pushing an error.
     */
    private static int expectReply(long L, byte[] reply, String fn, char want) {
        if (reply.length > 0 && reply[0] == '-') {
            luaPushError(L, latin1(reply));
            return -1;
        }
        int value = -1;
        if (want == '+' && Arrays.equals(reply, OK)) value = 1;
        else if (want == ':' && reply.length == 4 && reply[0] == ':' && (reply[1] == '0' || reply[1] == '1')
                && reply[2] == '\r' && reply[3] == '\n') value = reply[1] - '0';
        if (value < 0) luaPushError(L, "unexpected reply from handler for " + fn);
        return value;
    }

    private int luaSetResp(Lua l) {
        long L = l.getPointer();
        int argc = C.lua_gettop(L);
        if (argc != 1) {
            luaPushError(L, "server.setresp() requires one argument.");
            return -1;
        }
        int resp = d2i(C.lua_tonumber(L, -argc));
        if (resp != 2 && resp != 3) {
            luaPushError(L, "RESP version must be 2 or 3.");
            return -1;
        }
        if (expectReply(L, host(() -> handler.setResp(resp)), "server.setresp", '+') < 0) return -1;
        setScriptResp(L, resp);
        return 0;
    }

    private int luaRedisSetReplCommand(Lua l) {
        long L = l.getPointer();
        if (C.lua_gettop(L) != 1) {
            luaPushError(L, "server.set_repl() requires one argument.");
            return -1;
        }
        int flags = d2i(C.lua_tonumber(L, -1));
        if ((flags & ~3) != 0) {
            luaPushError(L, "Invalid replication flags. Use REPL_AOF, REPL_REPLICA, REPL_ALL or REPL_NONE.");
            return -1;
        }
        return expectReply(L, host(() -> handler.setRepl(flags)), "server.set_repl", '+') < 0 ? -1 : 0;
    }

    private int luaLogCommand(Lua l) {
        long L = l.getPointer();
        int argc = C.lua_gettop(L);
        if (argc < 2) {
            luaPushError(L, "server.log() requires two arguments or more.");
            return -1;
        } else if (C.lua_isnumber(L, -argc) == 0) {
            luaPushError(L, "First argument must be a number (log level).");
            return -1;
        }
        int level = d2i(C.lua_tonumber(L, -argc));
        if (level < 0 || level > 3) {
            luaPushError(L, "Invalid log level.");
            return -1;
        }
        // Glue together all the arguments, as the Wasm variant does (a non-string argument is skipped).
        var msg = new ByteArrayOutputStream();
        for (int j = 1; j < argc; j++) {
            byte[] s = toBytes(L, j + 1);
            if (s != null) {
                if (j != 1) msg.write(' ');
                msg.writeBytes(s);
            }
        }
        String m = new String(msg.toByteArray(), StandardCharsets.UTF_8);
        return expectReply(L, host(() -> handler.log(level, m)), "server.log", '+') < 0 ? -1 : 0;
    }

    private int luaRedisAclCheckCmdPermissionsCommand(Lua l) {
        long L = l.getPointer();
        List<byte[]> args = luaArgsToServerArgv(L);
        if (args == null) return -1;
        int allowed = expectReply(L, host(() -> handler.aclCheckCmd(args)), "server.acl_check_cmd", ':');
        if (allowed < 0) return -1;
        C.lua_pushboolean(L, allowed);
        return 1;
    }

    /** Per-eval reset: RESP2 and replicate to AOF and replicas. Run protected so a failure becomes the eval error. */
    private int resetHostState(Lua l) {
        long L = l.getPointer();
        if (expectReply(L, host(() -> handler.setResp(2)), "server.setresp", '+') < 0) return -1;
        if (expectReply(L, host(() -> handler.setRepl(3)), "server.set_repl", '+') < 0) return -1;
        return 0;
    }

    /* ---------------------------------------------------------------------------------------------------------
     * math.random / math.randomseed with Valkey's PRNG (script_lua.c, rand.c)
     * ------------------------------------------------------------------------------------------------------- */

    private int serverLrand48() {
        rand48 = (rand48 * 0x5DEECE66DL + 0xB) & 0xFFFFFFFFFFFFL;
        return (int) (rand48 >>> 17);
    }

    private int serverMathRandom(Lua l) {
        long L = l.getPointer();
        double r = (double) (serverLrand48() % Integer.MAX_VALUE) / (double) Integer.MAX_VALUE;
        switch (C.lua_gettop(L)) {
            case 0:
                C.lua_pushnumber(L, r); // Number between 0 and 1
                break;
            case 1: {
                Integer u = luaLCheckint(L, 1, "random");
                if (u == null) return -1;
                if (!(1 <= u)) return luaLArgerror(L, 1, "random", "interval is empty");
                C.lua_pushnumber(L, Math.floor(r * u) + 1); // int between 1 and `u'
                break;
            }
            case 2: {
                Integer lo = luaLCheckint(L, 1, "random");
                if (lo == null) return -1;
                Integer u = luaLCheckint(L, 2, "random");
                if (u == null) return -1;
                if (!(lo <= u)) return luaLArgerror(L, 2, "random", "interval is empty");
                C.lua_pushnumber(L, Math.floor(r * (u - lo + 1)) + lo); // int between `l' and `u'
                break;
            }
            default:
                return luaLError(L, "wrong number of arguments");
        }
        return 1;
    }

    private int serverMathRandomseed(Lua l) {
        long L = l.getPointer();
        Integer seed = luaLCheckint(L, 1, "randomseed");
        if (seed == null) return -1;
        rand48 = ((seed & 0xFFFFFFFFL) << 16) | 0x330E; // serverSrand48
        return 0;
    }

    /* ---------------------------------------------------------------------------------------------------------
     * lauxlib error helpers. Calling them through JNI would unwind Lua's error across Java frames, so they are
     * rebuilt here and raised by returning -1.
     * ------------------------------------------------------------------------------------------------------- */

    private static final String[] TYPE_NAMES =
            {"no value", "nil", "boolean", "userdata", "number", "string", "table", "function", "userdata", "thread"};

    /** luaL_error: the message prefixed with the caller's position (luaL_where(L, 1)). */
    private static int luaLError(long L, String msg) {
        C.luaL_where(L, 1);
        pushString(L, msg);
        C.lua_concat(L, 2);
        return -1;
    }

    // ponytail: luaL_argerror takes the function name from the call site; this hardcodes it, so a script that
    // aliases math.random sees 'random' instead of its local name.
    private static int luaLArgerror(long L, int narg, String fname, String extramsg) {
        return luaLError(L, "bad argument #" + narg + " to '" + fname + "' (" + extramsg + ")");
    }

    /** luaL_checkint, or null after pushing luaL_typerror's message. */
    private static Integer luaLCheckint(long L, int narg, String fname) {
        long d = C.lua_tointeger(L, narg);
        if (d == 0 && C.lua_isnumber(L, narg) == 0) {
            luaLArgerror(L, narg, fname, "number expected, got " + TYPE_NAMES[C.lua_type(L, narg) + 1]);
            return null;
        }
        return (int) d;
    }

    /* ---------------------------------------------------------------------------------------------------------
     * eval
     * ------------------------------------------------------------------------------------------------------- */

    /** kv is the RESP2 reply *2 [keys array, args array]. Sets globals KEYS and ARGV; returns 1 on malformed input. */
    private static int setKeysArgv(long L, byte[] kv) {
        boolean[] error = {false};
        if (kv.length == 0 || kv[0] != '*' || pushReply(L, kv, 0, error, 0) != kv.length || error[0]
                || C.lua_type(L, -1) != LUA_TTABLE || C.lua_objlen(L, -1) != 2) {
            C.lua_settop(L, 0);
            C.lua_pushstring(L, "ERR malformed KEYS/ARGV buffer");
            return 1;
        }
        // open global protection to set KEYS and ARGV
        enableReadonlyGlobals(L, false);
        C.lua_rawgeti(L, -1, 1);
        C.lua_setglobal(L, "KEYS");
        C.lua_rawgeti(L, -1, 2);
        C.lua_setglobal(L, "ARGV");
        enableReadonlyGlobals(L, true);
        C.lua_pop(L, 1);
        return 0;
    }

    /** Runs a script in a fresh state, as the Wasm variant does, and returns Valkey's RESP2 reply. */
    @Override
    Result evalUnchecked(Script script, byte[] kv) {
        return onLuaThread(() -> evalOnLuaThread(script, kv));
    }

    /** The parser recurses on the native stack too. */
    @Override
    Script compileUnchecked(String script) {
        byte[] out = onLuaThread(() -> compileScript(script.getBytes(StandardCharsets.UTF_8)));
        return out[0] == '-' ? new Script(null, null, script, out) : new Script(JniValkeyLua.class, out, null, null);
    }

    /** Runs task on a Valkey-sized stack and waits for it, even if interrupted: the instance isn't thread-safe. */
    private static <T> T onLuaThread(Callable<T> task) {
        Future<T> f = LUA_THREADS.submit(task);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return f.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException r) throw r;
                    if (e.getCause() instanceof Error r) throw r;
                    throw new IllegalStateException(e.getCause());
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Nothing can stop native Lua short of ending the process (README "No script timeout"). */
    @Override
    Result eval(Script script, byte[] kv, Duration timeout) {
        throw new UnsupportedOperationException("JniValkeyLua cannot stop a running script; use WasmValkeyLua");
    }

    private Result evalOnLuaThread(Script script, byte[] kv) {
        var lua = new Lua51();
        try {
            long L = lua.getPointer();
            // luajava's setup opens base and registers `java` (reflective access to the whole JVM), also in
            // _LOADED where debug.getregistry() reaches it. lua.wasm has neither.
            C.lua_pushnil(L);
            C.lua_setglobal(L, "java");
            C.lua_getfield(L, LUA_REGISTRYINDEX, "_LOADED");
            C.lua_pushnil(L);
            C.lua_setfield(L, -2, "java");
            C.lua_pop(L, 1);
            for (String lib : List.of("", "table", "string", "math", "debug", "os")) C.luaJ_openlib(L, lib);
            openValkeyLibs(L);

            C.lua_newtable(L);
            setField(L, "call", l -> redisGenericCommand(l, true));
            setField(L, "pcall", l -> redisGenericCommand(l, false));
            setField(L, "sha1hex", JniValkeyLua::luaRedisSha1hexCommand);
            setField(L, "error_reply", JniValkeyLua::luaRedisErrorReplyCommand);
            setField(L, "status_reply", l -> luaRedisReturnSingleFieldTable(l, "ok"));
            setField(L, "replicate_commands", l -> {
                C.lua_pushboolean(l.getPointer(), 1);
                return 1;
            });
            // No debugger here, so these take the "not active" branches of the upstream functions.
            setField(L, "breakpoint", l -> {
                C.lua_pushboolean(l.getPointer(), 0);
                return 1;
            });
            setField(L, "debug", l -> 0);
            setField(L, "setresp", this::luaSetResp);
            setField(L, "set_repl", this::luaRedisSetReplCommand);
            setField(L, "log", this::luaLogCommand);
            setField(L, "acl_check_cmd", this::luaRedisAclCheckCmdPermissionsCommand);
            setNumber(L, "REPL_NONE", 0);
            setNumber(L, "REPL_AOF", 1);
            setNumber(L, "REPL_SLAVE", 2);
            setNumber(L, "REPL_REPLICA", 2);
            setNumber(L, "REPL_ALL", 3);
            setNumber(L, "LOG_DEBUG", 0);
            setNumber(L, "LOG_VERBOSE", 1);
            setNumber(L, "LOG_NOTICE", 2);
            setNumber(L, "LOG_WARNING", 3);
            setNumber(L, "REDIS_VERSION_NUM", parseSemver(info.redisVersion()));
            setString(L, "REDIS_VERSION", info.redisVersion());
            setNumber(L, "VALKEY_VERSION_NUM", parseSemver(info.valkeyVersion()));
            setString(L, "VALKEY_VERSION", info.valkeyVersion());
            setString(L, "SERVER_NAME", info.serverName());
            C.lua_pushvalue(L, -1);
            C.lua_setglobal(L, "server");
            C.lua_setglobal(L, "redis");
            C.luaJ_pushfunction(L, (JFunction) JniValkeyLua::luaRedisPcall);
            C.lua_setglobal(L, "pcall");

            // Replace math.random and math.randomseed with custom implementations.
            C.lua_getglobal(L, "math");
            setField(L, "random", this::serverMathRandom);
            setField(L, "randomseed", this::serverMathRandomseed);
            C.lua_setglobal(L, "math");
            installErrorHandler(L);
            lockGlobals(L);

            int kind = EVAL_SETUP;
            C.luaJ_pushfunction(L, (JFunction) this::resetHostState);
            if (C.lua_pcall(L, 0, 0, 0) == 0 && setKeysArgv(L, kv) == 0) {
                kind = EVAL_COMPILE;
                if (load(L, script) == 0) {
                    pushErrorHandler(L);
                    C.lua_insert(L, -2);
                    kind = C.lua_pcall(L, 0, 1, -2) != 0 ? EVAL_RUN : EVAL_OK;
                    C.lua_remove(L, -2); // the error handler
                }
            }

            byte[] reply = evalReply(L, kind);
            if (reply == null) throw new IllegalStateException("reply conversion failed");
            return new Result(kind == EVAL_OK, reply);
        } finally {
            lua.close();
        }
    }

    private static int load(long L, Script script) {
        if (script.bytecode != null) return loadCompiled(L, script.bytecode);
        byte[] src = script.source.getBytes(StandardCharsets.UTF_8);
        return C.luaJ_loadbuffer(L, ByteBuffer.allocateDirect(src.length).put(src), src.length, "@user_script");
    }

    /* ---------------------------------------------------------------------------------------------------------
     * Helpers
     * ------------------------------------------------------------------------------------------------------- */

    private static void setField(long L, String name, JFunction f) {
        C.luaJ_pushfunction(L, f);
        C.lua_setfield(L, -2, name);
    }

    private static void setNumber(long L, String name, double n) {
        C.lua_pushnumber(L, n);
        C.lua_setfield(L, -2, name);
    }

    private static void setString(long L, String name, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        pushlstring(L, b, 0, b.length);
        C.lua_setfield(L, -2, name);
    }

    /** Pushes a byte string held as ISO-8859-1. */
    private static void pushString(long L, String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        pushlstring(L, b, 0, b.length);
    }

    /** lua_tolstring as bytes (numbers are converted in place, as in C), or null if not a string or number. */
    private static byte[] toBytes(long L, int index) {
        var buf = (ByteBuffer) C.luaJ_tobuffer(L, index);
        if (buf == null) return null;
        byte[] b = new byte[buf.remaining()];
        buf.get(b);
        return b;
    }

    private static String latin1(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    /** lm_strtrim(s, "\r\n"). */
    private static String trimCrlf(String s) {
        int start = 0, end = s.length();
        while (start < end && (s.charAt(start) == '\r' || s.charAt(start) == '\n')) start++;
        while (end > start && (s.charAt(end - 1) == '\r' || s.charAt(end - 1) == '\n')) end--;
        return s.substring(start, end);
    }

    private static final Pattern SEMVER = Pattern.compile("^(\\d+)(?:\\.(\\d+)(?:\\.(\\d+))?)?");

    /** parse_semver: sscanf("%u.%u.%u"), missing parts are 0. */
    private static int parseSemver(String version) {
        var m = SEMVER.matcher(version);
        long[] v = new long[3];
        if (m.find()) {
            for (int i = 0; i < 3; i++) v[i] = m.group(i + 1) == null ? 0 : Long.parseLong(m.group(i + 1));
        }
        return (int) (((v[0] & 0xFF) << 16) | ((v[1] & 0xFF) << 8) | (v[2] & 0xFF));
    }
}
