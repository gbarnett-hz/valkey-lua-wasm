package valkeylua;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WasmValkeyLuaTest extends ValkeyLuaTest {

    static final Path WASM = Path.of("../lua/lua.wasm");

    @Override
    ValkeyLua create(Handler handler, ServerInfo info) {
        return new WasmValkeyLua(WASM, handler, info);
    }

    // Wasm-only: JNI runs Lua's C code on the thread's native stack and needs more (README, JNI backend).
    @Test
    void nestingErrorsAtMinimumThreadStack() throws Exception {
        // The documented minimum JVM thread stack for Wasm evals is 512 KiB (wasm-difference.md section 7).
        var results = new ArrayList<ValkeyLua.Result>();
        var failure = new AtomicReference<Throwable>();
        var t = new Thread(null, () -> {
            try {
                var l = create(args -> "+OK\r\n".getBytes(UTF_8));
                for (String[] c : NESTING) results.add(l.eval(c[0]));
                results.add(l.eval("return 1"));
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "eval-512k", 512 * 1024);
        t.start();
        t.join();
        if (failure.get() != null) throw new AssertionError("eval thread failed", failure.get());
        for (int i = 0; i < NESTING.length; i++) assertEquals(new ValkeyLua.Result(false, NESTING[i][1]), results.get(i));
        assertEquals(new ValkeyLua.Result(true, "1"), results.get(NESTING.length));
    }

    @Test
    void memoryCapIsAnOrdinaryLuaError() {
        // Chicory caps linear memory at 32767 pages; JNI succeeds here, so this is Wasm-only (wasm-difference.md section 8).
        // Doubling by concatenation (bulk copies) reaches the cap in about a second; string.rep builds byte by byte.
        assertEquals(new ValkeyLua.Result(false, "not enough memory"),
            lua.eval("local s = 'x' for i = 1, 31 do s = s .. s end return #s"));
        assertEquals(new ValkeyLua.Result(true, "1"), lua.eval("return 1"));
    }
}
