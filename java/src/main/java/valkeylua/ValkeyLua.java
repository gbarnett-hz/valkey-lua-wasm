package valkeylua;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Runs scripts on Valkey's bundled Lua with the server.* API delegated to a {@link Handler}. Two backends:
 * {@link WasmValkeyLua} (lua.wasm on Chicory) and {@link JniValkeyLua} (native Lua through luajava). Not thread-safe.
 */
public abstract sealed class ValkeyLua permits WasmValkeyLua, JniValkeyLua {

    /**
     * C's conversion of an out-of-range or NaN double to an integer is undefined, so Valkey gets its CPU's result:
     * arm64 saturates (NaN gives 0), as Java's casts do; x86-64 gives INT_MIN/LLONG_MIN.
     */
    static final boolean X86 = Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"));

    /** C's (int) of a double on this CPU. */
    static int d2i(double d) {
        return X86 && !(d > -0x1p31 - 1 && d < 0x1p31) ? Integer.MIN_VALUE : (int) d;
    }

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

    /**
     * A script compiled once by {@link #compile} for evals on any instance of the backend that compiled it, as Valkey
     * keeps EVAL and SCRIPT LOAD scripts compiled by SHA1 (the caller keeps the SHA1 map). An eval of it replies as an
     * eval of its source would. Immutable, so instances on any thread can share it.
     */
    public static final class Script {
        // The backend whose bytecode this is; null if not compiled.
        final Class<? extends ValkeyLua> backend;
        // Exactly one is set: bytecode (Lua's dump, for that backend's build only) or source.
        final byte[] bytecode;
        final String source;
        private final byte[] error;

        Script(Class<? extends ValkeyLua> backend, byte[] bytecode, String source, byte[] error) {
            this.backend = backend;
            this.bytecode = bytecode;
            this.source = source;
            this.error = error;
        }

        /**
         * Null if the script compiled; else the RESP2 error Valkey's SCRIPT LOAD replies, as an eval's reply ends up
         * (the eval compiles the source again, so its setup's handler calls still happen first).
         */
        public byte[] error() {
            return error == null ? null : error.clone();
        }
    }

    // Set once anything escapes eval; the backend's state can no longer be trusted.
    private boolean poisoned;

    ValkeyLua() {}

    void clearPoisoned() {
        poisoned = false;
    }

    public Result eval(String script) {
        return eval(script, List.of(), List.of());
    }

    public Result eval(String script, List<byte[]> keys, List<byte[]> args) {
        return eval(script, kv(keys, args));
    }

    /**
     * Throws {@link ScriptTimeoutException} once timeout (wall time from the start of the eval, handler calls included)
     * has passed. Wasm only: {@link JniValkeyLua} throws {@link UnsupportedOperationException}.
     */
    public Result eval(String script, Duration timeout) {
        return eval(script, List.of(), List.of(), timeout);
    }

    public Result eval(String script, List<byte[]> keys, List<byte[]> args, Duration timeout) {
        return eval(source(script), kv(keys, args), timeout);
    }

    /** Compiles a script for {@link #eval(Script, List, List)} on instances of this backend. */
    public Script compile(String script) {
        checkUsable();
        try {
            return compileUnchecked(script);
        } catch (Throwable e) {
            poisoned = true;
            throw e;
        }
    }

    /** Throws IllegalArgumentException if another backend compiled script. */
    public Result eval(Script script, List<byte[]> keys, List<byte[]> args) {
        return eval(script, kv(keys, args));
    }

    /** As {@link #eval(String, List, List, Duration)}. */
    public Result eval(Script script, List<byte[]> keys, List<byte[]> args, Duration timeout) {
        return eval(script, kv(keys, args), timeout);
    }

    private static Script source(String script) {
        return new Script(null, null, script, null);
    }

    private static byte[] kv(List<byte[]> keys, List<byte[]> args) {
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
        return kvOut.toByteArray();
    }

    void checkUsable() {
        if (poisoned) {
            throw new IllegalStateException("ValkeyLua instance is unusable after an escaped exception; create a new one");
        }
    }

    /** Package-private seam so tests can pass a raw (possibly malformed) KEYS/ARGV buffer. */
    Result eval(String script, byte[] kv) {
        return eval(source(script), kv);
    }

    Result eval(Script script, byte[] kv) {
        if (script.bytecode != null && script.backend != getClass()) {
            throw new IllegalArgumentException("script compiled by " + script.backend.getSimpleName());
        }
        checkUsable();
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
    abstract Result evalUnchecked(Script script, byte[] kv);

    abstract Result eval(Script script, byte[] kv, Duration timeout);

    abstract Script compileUnchecked(String script);
}
