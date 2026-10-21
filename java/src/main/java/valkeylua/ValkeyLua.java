package valkeylua;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.ValType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/** Runs scripts on Valkey's bundled Lua compiled to lua.wasm. Not thread-safe. */
public final class ValkeyLua {

    public record Result(boolean ok, String value) {}

    private final Instance instance;

    /**
     * @param redisCall handles redis.call/redis.pcall: receives the command arguments, returns a RESP2 reply
     */
    public ValkeyLua(Path wasm, Function<List<byte[]>, byte[]> redisCall) {
        var wasi = WasiPreview1.builder().withOptions(WasiOptions.builder().build()).build();
        var redis = new HostFunction("env", "redis_call",
                FunctionType.of(List.of(ValType.I32, ValType.I32, ValType.I32, ValType.I32), List.of()),
                // inst is the calling Wasm instance; args are (req_ptr, req_len, out_ptr, out_len) into its memory.
                (inst, args) -> {
                    byte[] req = inst.memory().readBytes((int) args[0], (int) args[1]);
                    byte[] reply = redisCall.apply(parseRequest(req));
                    // Copy the reply into a guest-allocated buffer; the C side owns and frees it.
                    int ptr = (int) inst.export("alloc").apply(reply.length)[0];
                    inst.memory().write(ptr, reply);
                    // Wasm has no multi-value return here, so hand back pointer and length via the out-params.
                    inst.memory().writeI32((int) args[2], ptr);
                    inst.memory().writeI32((int) args[3], reply.length);
                    return null;
                });
        var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).addFunction(redis).build();
        // Compile lua.wasm to JVM bytecode at instantiation; ~20-40x faster than the default interpreter.
        instance = Instance.builder(Parser.parse(wasm))
                .withImportValues(imports)
                .withMachineFactory(MachineFactoryCompiler::compile)
                .withStart(false)
                .build();
        instance.export("_initialize").apply();
    }

    public Result eval(String script) {
        byte[] src = script.getBytes(StandardCharsets.UTF_8);
        int ptr = (int) instance.export("alloc").apply(src.length)[0];
        try {
            instance.memory().write(ptr, src);
            long status = instance.export("eval").apply(ptr, src.length)[0];
            int resPtr = (int) instance.export("result_ptr").apply()[0];
            int resLen = (int) instance.export("result_len").apply()[0];
            String value = new String(instance.memory().readBytes(resPtr, resLen), StandardCharsets.UTF_8);
            return new Result(status == 0, value);
        } finally {
            instance.export("dealloc").apply(ptr);
        }
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
