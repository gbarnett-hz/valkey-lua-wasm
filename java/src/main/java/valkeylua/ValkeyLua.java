package valkeylua;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Runs scripts on Valkey's bundled Lua with the server.* API delegated to a {@link Handler}. Two backends:
 * {@link WasmValkeyLua} (lua.wasm on Chicory) and {@link JniValkeyLua} (native Lua through luajava). Not thread-safe.
 */
public abstract sealed class ValkeyLua permits WasmValkeyLua, JniValkeyLua {

    /**
     * reply is the RESP2 reply Valkey would send the client. ok is false when the script raised, failed to compile, or
     * the per-eval setup failed; a script that returns {err=...} gets ok=true with an error reply, as on Valkey.
     */
    public record Result(boolean ok, byte[] reply) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Result r && ok == r.ok && Arrays.equals(reply, r.reply);
        }

        @Override
        public int hashCode() {
            return Boolean.hashCode(ok) * 31 + Arrays.hashCode(reply);
        }

        @Override
        public String toString() {
            String text = new String(reply, StandardCharsets.ISO_8859_1).replace("\r", "\\r").replace("\n", "\\n");
            return "Result[ok=" + ok + ", reply=" + text + "]";
        }
    }

    // Set once anything escapes eval; the backend's state can no longer be trusted.
    private boolean poisoned;

    ValkeyLua() {}

    public Result eval(String script) {
        return eval(script, List.of(), List.of());
    }

    public Result eval(String script, List<byte[]> keys, List<byte[]> args) {
        var kvOut = new ByteArrayOutputStream();
        kvOut.writeBytes("*2\r\n".getBytes(StandardCharsets.US_ASCII));
        for (var list : List.of(keys, args)) {
            kvOut.writeBytes(("*" + list.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (byte[] e : list) {
                kvOut.writeBytes(("$" + e.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                kvOut.writeBytes(e);
                kvOut.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
        }
        return eval(script, kvOut.toByteArray());
    }

    /** Package-private seam so tests can pass a raw (possibly malformed) KEYS/ARGV buffer. */
    Result eval(String script, byte[] kv) {
        if (poisoned) {
            throw new IllegalStateException("ValkeyLua instance is unusable after an escaped exception; create a new one");
        }
        try {
            return evalUnchecked(script, kv);
        } catch (Throwable e) {
            poisoned = true;
            throw e;
        }
    }

    /** Runs a handler method; anything thrown, or a null reply, becomes an -ERR reply. */
    static byte[] host(Supplier<byte[]> op) {
        try {
            return Objects.requireNonNull(op.get(), "handler returned null");
        } catch (Throwable e) {
            String msg = e.getMessage();
            msg = msg == null ? e.getClass().getSimpleName() : msg.replace('\r', ' ').replace('\n', ' ');
            return ("-ERR " + msg + "\r\n").getBytes(StandardCharsets.UTF_8);
        }
    }

    /** kv is the RESP2 reply *2 [keys array, args array]. */
    abstract Result evalUnchecked(String script, byte[] kv);
}
