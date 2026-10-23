package membench;

import java.nio.charset.StandardCharsets;
import java.util.List;
import valkeylua.Handler;
import valkeylua.JniValkeyLua;
import valkeylua.ValkeyLua;
import valkeylua.WasmValkeyLua;

/** Workloads shared by the JMH benchmark and the footprint probe. */
final class Scenarios {

    static final List<byte[]> KEYS = List.of("key:1".getBytes(StandardCharsets.UTF_8));
    static final List<byte[]> ARGS = List.of("v".repeat(100).getBytes(StandardCharsets.UTF_8));
    // Stub redis.call: a fixed 100-byte bulk reply, so host-call cost is the same on both backends.
    static final byte[] BULK = ("$100\r\n" + "v".repeat(100) + "\r\n").getBytes(StandardCharsets.UTF_8);
    static final Handler HANDLER = args -> BULK;

    static String script(String name) {
        return switch (name) {
            case "noop" -> "return 1";
            // 100 host round trips with a 100-byte value each way.
            case "calls" -> "for i = 1, 100 do redis.call('SET', KEYS[1], ARGV[1]) end return redis.call('GET', KEYS[1])";
            // 1000-element table through cjson both ways.
            case "json" -> "local t = {} for i = 1, 1000 do t[i] = {id = i, name = 'item' .. i} end "
                    + "return #cjson.decode(cjson.encode(t))";
            // 10k-element array reply (~70 KB of RESP).
            case "reply" -> "local t = {} for i = 1, 10000 do t[i] = i end return t";
            // 16 MiB transient Lua string; the reply is just its length.
            case "bigstr" -> "local s = string.rep('x', 16 * 1024 * 1024) return #s";
            default -> throw new IllegalArgumentException(name);
        };
    }

    static ValkeyLua create(String backend) {
        return switch (backend) {
            case "wasm" -> new WasmValkeyLua(HANDLER);
            case "jni" -> new JniValkeyLua(HANDLER);
            default -> throw new IllegalArgumentException(backend);
        };
    }

    static ValkeyLua.Result eval(ValkeyLua l, String script) {
        var r = l.eval(script, KEYS, ARGS);
        if (!r.ok()) throw new IllegalStateException(r.toString());
        return r;
    }

    private Scenarios() {}
}
