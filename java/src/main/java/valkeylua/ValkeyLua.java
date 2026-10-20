package valkeylua;

import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Runs scripts on Valkey's bundled Lua compiled to lua.wasm. Not thread-safe. */
public final class ValkeyLua {

    public record Result(boolean ok, String value) {}

    private final Instance instance;

    public ValkeyLua(Path wasm) {
        var wasi = WasiPreview1.builder().withOptions(WasiOptions.builder().build()).build();
        var imports = ImportValues.builder().addFunction(wasi.toHostFunctions()).build();
        instance = Instance.builder(Parser.parse(wasm)).withImportValues(imports).withStart(false).build();
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
}
