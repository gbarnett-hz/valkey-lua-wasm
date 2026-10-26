package valkeylua;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.ChicoryInterruptedException;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.runtime.Machine;
import com.dylibso.chicory.runtime.Memory;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Runs scripts on Valkey's bundled Lua compiled to lua.wasm (lua/Makefile), on Chicory. If anything escapes eval the
 * Wasm shadow stack pointer is not restored, so the instance is poisoned.
 */
public final class WasmValkeyLua extends ValkeyLua {

    private final ImportValues imports;
    private final ServerInfo info;
    private final long maxRetainedBytes;
    private final ScheduledExecutorService watchdog;
    private Instance instance;
    // Set during a timed eval.
    private Deadline deadline;

    /** Default for maxRetainedBytes: scripts are expected to be small. */
    public static final long DEFAULT_MAX_RETAINED_BYTES = 128L << 20;

    // Runs the deadline checks of instances given no watchdog; its one daemon thread starts on the first timed eval.
    private static final ScheduledExecutorService SHARED_WATCHDOG = sharedWatchdog();

    // Op codes of env.host_call; keep in sync with the enum in valkey_lua.c.
    private static final int OP_CALL = 0, OP_SETRESP = 1, OP_SET_REPL = 2, OP_LOG = 3, OP_ACL_CHECK_CMD = 4;

    public WasmValkeyLua(Handler handler) {
        this(handler, ServerInfo.DEFAULT);
    }

    public WasmValkeyLua(Handler handler, ServerInfo info) {
        this(handler, info, DEFAULT_MAX_RETAINED_BYTES);
    }

    /**
     * Wasm memory never shrinks, so after an eval leaves it larger than maxRetainedBytes the instance is rebuilt,
     * releasing the memory. Nothing a script can observe carries over in the old instance except math.random's state,
     * which is copied.
     */
    public WasmValkeyLua(Handler handler, ServerInfo info, long maxRetainedBytes) {
        this(handler, info, maxRetainedBytes, SHARED_WATCHDOG);
    }

    /** watchdog runs the deadline checks of timed evals, in place of a daemon thread shared by all instances. */
    public WasmValkeyLua(Handler handler, ServerInfo info, long maxRetainedBytes,
            ScheduledExecutorService watchdog) {
        this.watchdog = watchdog;
        var wasi = WasiPreview1.builder().withOptions(WasiOptions.builder().build()).build();
        var redis = new HostFunction("env", "host_call",
                FunctionType.of(List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of()),
                // inst is the calling Wasm instance; args are (op, req_ptr, req_len, out_ptr, out_len) into its memory.
                (inst, args) -> {
                    // The handler runs with the deadline unable to interrupt it.
                    var d = deadline;
                    if (d != null) d.leaveLua();
                    byte[] req = inst.memory().readBytes((int) args[1], (int) args[2]);
                    byte[] reply = host(() -> dispatch(handler, (int) args[0], parseRequest(req)));
                    if (d != null) d.enterLua();
                    // Copy the reply into a guest-allocated buffer; the C side owns and frees it.
                    int ptr = (int) inst.export("alloc").apply(reply.length)[0];
                    inst.memory().write(ptr, reply);
                    // Wasm has no multi-value return here, so hand back pointer and length via the out-params.
                    inst.memory().writeI32((int) args[3], ptr);
                    inst.memory().writeI32((int) args[4], reply.length);
                    return null;
                });
        imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).addFunction(redis).build();
        this.info = info;
        this.maxRetainedBytes = maxRetainedBytes;
        rebuild();
    }

    /**
     * Replaces the instance with a fresh one (well under a millisecond) and makes a poisoned instance usable again. As with the
     * rebuild past maxRetainedBytes, only math.random's state carries over.
     */
    public void reset() {
        rebuild();
        clearPoisoned();
    }

    /** Replaces the instance with a fresh one, dropping its linear memory. */
    void rebuild() {
        var old = instance;
        instance = Instance.builder(Compiled.MODULE)
                .withImportValues(imports)
                .withMachineFactory(Compiled.MACHINE_FACTORY)
                .withStart(false)
                .build();
        instance.export("_initialize").apply();
        if (X86) instance.export("set_x86_cast").apply(1);
        setServerInfo(info);
        if (old != null) instance.export("set_rand_state").apply(old.export("rand_state").apply()[0]);
    }

    long memoryBytes() {
        return (long) instance.memory().pages() * Memory.PAGE_SIZE;
    }

    private void setServerInfo(ServerInfo info) {
        var req = new StringBuilder("*3\r\n");
        for (String f : List.of(info.redisVersion(), info.valkeyVersion(), info.serverName())) {
            req.append('$').append(f.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(f).append("\r\n");
        }
        byte[] b = req.toString().getBytes(StandardCharsets.UTF_8);
        int ptr = (int) instance.export("alloc").apply(b.length)[0];
        try {
            instance.memory().write(ptr, b);
            instance.export("set_server_info").apply(ptr, b.length);
        } finally {
            instance.export("dealloc").apply(ptr);
        }
    }

    @Override
    Result evalUnchecked(String script, byte[] kv) {
        try {
            return run(script, kv);
        } catch (RuntimeException e) {
            // Chicory or a host call may have wrapped it.
            for (Throwable c = e; c != null; c = c.getCause()) {
                if (c instanceof ChicoryInterruptedException) throw new ScriptInterruptedException(c);
            }
            throw e;
        }
    }

    private Result run(String script, byte[] kv) {
        byte[] src = script.getBytes(StandardCharsets.UTF_8);
        int ptr = (int) instance.export("alloc").apply(src.length)[0];
        int kvPtr = (int) instance.export("alloc").apply(kv.length)[0];
        Result result;
        try {
            instance.memory().write(ptr, src);
            instance.memory().write(kvPtr, kv);
            if (deadline != null) deadline.enterLua();
            int status;
            try {
                status = (int) instance.export("eval").apply(ptr, src.length, kvPtr, kv.length)[0];
            } finally {
                // A script that finished keeps its result even if the deadline passed as it returned.
                if (deadline != null) deadline.finish();
            }
            if (status < 0) throw new IllegalStateException("reply conversion failed");
            int resPtr = (int) instance.export("result_ptr").apply()[0];
            int resLen = (int) instance.export("result_len").apply()[0];
            result = new Result(status == 0, instance.memory().readBytes(resPtr, resLen));
        } finally {
            instance.export("dealloc").apply(ptr);
            instance.export("dealloc").apply(kvPtr);
        }
        if (memoryBytes() > maxRetainedBytes) rebuild();
        return result;
    }

    @Override
    Result eval(String script, byte[] kv, Duration timeout) {
        checkUsable();
        var d = new Deadline(timeout);
        var check = watchdog.schedule(d::expire, timeout.toNanos(), TimeUnit.NANOSECONDS);
        deadline = d;
        try {
            return eval(script, kv);
        } catch (RuntimeException | Error e) {
            if (!d.finish()) throw e;
            // The flag can only be someone else's here; rebuilding must not trip on it.
            boolean interrupted = Thread.interrupted();
            try {
                reset();
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            throw new ScriptTimeoutException(timeout);
        } finally {
            deadline = null;
            check.cancel(false);
        }
    }

    /**
     * One timed eval's deadline. The watchdog interrupts the eval thread only while Lua runs (IN); otherwise (OUT: before
     * Lua starts or in a handler call) it only marks the deadline EXPIRED and the eval thread stops as it enters Lua.
     * Leaving Lua waits out an interrupt the watchdog has committed to (INTERRUPTING) and consumes it.
     */
    private static final class Deadline {
        private static final int OUT = 0, IN = 1, EXPIRED = 2, INTERRUPTING = 3, INTERRUPTED = 4, DONE = 5;
        private final AtomicInteger state = new AtomicInteger(OUT);
        private final Thread owner = Thread.currentThread();
        private final Duration timeout;
        // Whether the watchdog interrupted owner; written before INTERRUPTED.
        private volatile boolean sent;
        private boolean timedOut;

        Deadline(Duration timeout) {
            this.timeout = timeout;
        }

        /** Watchdog thread. */
        void expire() {
            while (true) {
                int s = state.get();
                if (s == OUT && state.compareAndSet(OUT, EXPIRED)) return;
                if (s == IN && state.compareAndSet(IN, INTERRUPTING)) {
                    // A flag already set stops Lua anyway; interrupting again would merge ours into it, and consuming
                    // ours would then clear someone else's. ponytail: an interrupt landing between this check and ours
                    // still merges and is consumed; telling them apart needs a count hook instead of interrupts.
                    sent = !owner.isInterrupted();
                    if (sent) owner.interrupt();
                    state.set(INTERRUPTED);
                    return;
                }
                if (s != OUT && s != IN) return;
            }
        }

        void enterLua() {
            if (!state.compareAndSet(OUT, IN)) throw new ScriptTimeoutException(timeout);
        }

        void leaveLua() {
            if (!state.compareAndSet(IN, OUT)) {
                consumeInterrupt();
                throw new ScriptTimeoutException(timeout);
            }
        }

        /** Ends the deadline; true if it passed while Lua could still run. */
        boolean finish() {
            while (true) {
                int s = state.get();
                if (s == DONE) return timedOut;
                if ((s == IN || s == OUT) && state.compareAndSet(s, DONE)) return false;
                if (s == EXPIRED || s == INTERRUPTING || s == INTERRUPTED) {
                    consumeInterrupt();
                    timedOut = true;
                    state.set(DONE);
                    return true;
                }
            }
        }

        private void consumeInterrupt() {
            while (state.get() == INTERRUPTING) Thread.onSpinWait();
            if (state.get() == INTERRUPTED && sent) Thread.interrupted();
            state.set(EXPIRED);
        }
    }

    /**
     * lua.wasm, parsed and compiled once per JVM on first use. Every instance shares the module and the generated
     * classes (so HotSpot profiles and compiles them once); each has its own Instance, linear memory and globals.
     */
    private static final class Compiled {
        // Bundled by the build (java/pom.xml runs make -C ../lua).
        static final WasmModule MODULE = Parser.parse(WasmValkeyLua.class.getResourceAsStream("lua.wasm"));
        // Compiled to JVM bytecode; ~20-40x faster than the default interpreter. Stateless, so shared across threads.
        static final Function<Instance, Machine> MACHINE_FACTORY = MachineFactoryCompiler.compile(MODULE);
    }

    private static ScheduledExecutorService sharedWatchdog() {
        var e = new ScheduledThreadPoolExecutor(1, r -> {
            var t = new Thread(r, "valkey-lua-watchdog");
            t.setDaemon(true);
            return t;
        });
        // Most evals finish in time; don't keep their cancelled checks queued until the deadline.
        e.setRemoveOnCancelPolicy(true);
        return e;
    }

    private static byte[] dispatch(Handler h, int op, List<byte[]> args) {
        return switch (op) {
            case OP_CALL -> h.call(args);
            case OP_SETRESP -> h.setResp(Integer.parseInt(str(args.get(0))));
            case OP_SET_REPL -> h.setRepl(Integer.parseInt(str(args.get(0))));
            case OP_LOG -> h.log(Integer.parseInt(str(args.get(0))), str(args.get(1)));
            case OP_ACL_CHECK_CMD -> h.aclCheckCmd(args);
            default -> throw new IllegalStateException("unknown host op " + op);
        };
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Parses the RESP2 array of bulk strings built by valkey_lua.c. */
    private static List<byte[]> parseRequest(byte[] req) {
        int[] pos = {0};
        int argc = readInt(req, pos, '*');
        var args = new ArrayList<byte[]>(argc);
        for (int i = 0; i < argc; i++) {
            int len = readInt(req, pos, '$');
            args.add(Arrays.copyOfRange(req, pos[0], pos[0] + len));
            pos[0] += len + 2;
        }
        return args;
    }

    private static int readInt(byte[] buf, int[] pos, char prefix) {
        if (buf[pos[0]] != prefix) throw new IllegalStateException("expected '" + prefix + "' at " + pos[0]);
        int n = 0;
        for (pos[0]++; buf[pos[0]] != '\r'; pos[0]++) n = n * 10 + (buf[pos[0]] - '0');
        pos[0] += 2;
        return n;
    }
}
