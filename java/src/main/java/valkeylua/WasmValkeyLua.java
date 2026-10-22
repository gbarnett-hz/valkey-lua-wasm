package valkeylua;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Runs scripts on Valkey's bundled Lua compiled to lua.wasm (lua/Makefile), on Chicory. If anything escapes eval the
 * Wasm shadow stack pointer is not restored, so the instance is poisoned.
 */
public final class WasmValkeyLua extends ValkeyLua {

    private final WasmModule module;
    private final Function<Instance, Machine> machineFactory;
    private final ImportValues imports;
    private final ServerInfo info;
    private final long maxRetainedBytes;
    private Instance instance;
    private static final boolean X86 = Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"));

    /** Default for maxRetainedBytes: scripts are expected to be small. */
    public static final long DEFAULT_MAX_RETAINED_BYTES = 128L << 20;

    // Op codes of env.host_call; keep in sync with the enum in valkey_lua.c.
    private static final int OP_CALL = 0, OP_SETRESP = 1, OP_SET_REPL = 2, OP_LOG = 3, OP_ACL_CHECK_CMD = 4;

    public WasmValkeyLua(Path wasm, Handler handler) {
        this(wasm, handler, ServerInfo.DEFAULT);
    }

    public WasmValkeyLua(Path wasm, Handler handler, ServerInfo info) {
        this(wasm, handler, info, DEFAULT_MAX_RETAINED_BYTES);
    }

    /**
     * Wasm memory never shrinks, so after an eval leaves it larger than maxRetainedBytes the instance is rebuilt,
     * releasing the memory. Nothing a script can observe carries over in the old instance except math.random's state,
     * which is copied.
     */
    public WasmValkeyLua(Path wasm, Handler handler, ServerInfo info, long maxRetainedBytes) {
        var wasi = WasiPreview1.builder().withOptions(WasiOptions.builder().build()).build();
        var redis = new HostFunction("env", "host_call",
                FunctionType.of(List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of()),
                // inst is the calling Wasm instance; args are (op, req_ptr, req_len, out_ptr, out_len) into its memory.
                (inst, args) -> {
                    byte[] req = inst.memory().readBytes((int) args[1], (int) args[2]);
                    byte[] reply = host(() -> dispatch(handler, (int) args[0], parseRequest(req)));
                    // Copy the reply into a guest-allocated buffer; the C side owns and frees it.
                    int ptr = (int) inst.export("alloc").apply(reply.length)[0];
                    inst.memory().write(ptr, reply);
                    // Wasm has no multi-value return here, so hand back pointer and length via the out-params.
                    inst.memory().writeI32((int) args[3], ptr);
                    inst.memory().writeI32((int) args[4], reply.length);
                    return null;
                });
        imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).addFunction(redis).build();
        module = Parser.parse(wasm);
        // Compile lua.wasm to JVM bytecode once; ~20-40x faster than the default interpreter.
        machineFactory = MachineFactoryCompiler.compile(module);
        this.info = info;
        this.maxRetainedBytes = maxRetainedBytes;
        rebuild();
    }

    /** Replaces the instance with a fresh one, dropping its linear memory. */
    void rebuild() {
        var old = instance;
        instance = Instance.builder(module)
                .withImportValues(imports)
                .withMachineFactory(machineFactory)
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
        byte[] src = script.getBytes(StandardCharsets.UTF_8);
        int ptr = (int) instance.export("alloc").apply(src.length)[0];
        int kvPtr = (int) instance.export("alloc").apply(kv.length)[0];
        Result result;
        try {
            instance.memory().write(ptr, src);
            instance.memory().write(kvPtr, kv);
            int status = (int) instance.export("eval").apply(ptr, src.length, kvPtr, kv.length)[0];
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
