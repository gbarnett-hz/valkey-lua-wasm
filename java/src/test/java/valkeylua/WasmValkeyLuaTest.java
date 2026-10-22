package valkeylua;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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
        // The documented minimum JVM thread stack for Wasm evals is 512 KiB (gaps.md section 4).
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
        for (int i = 0; i < NESTING.length; i++) assertEquals(result(false, NESTING[i][1]), results.get(i));
        assertEquals(result(true, integer(1)), results.get(NESTING.length));
    }

    @Test
    void memoryCapIsAnOrdinaryLuaError() {
        // Chicory caps linear memory at 32767 pages; JNI succeeds here, so this is Wasm-only (gaps.md section 5).
        // Doubling by concatenation (bulk copies) reaches the cap in about a second; string.rep builds byte by byte.
        assertEquals(result(false, error("ERR Error running script, not enough memory")),
            lua.eval("local s = 'x' for i = 1, 31 do s = s .. s end return #s"));
        assertEquals(result(true, integer(1)), lua.eval("return 1"));
    }

    @Test
    void memoryAboveMaxRetainedIsReleasedAndRandomContinues() {
        // Wasm memory never shrinks; the instance is rebuilt instead (gaps.md section 5). The rebuild must not restart
        // math.random, whose state lives for the instance on Valkey and JNI.
        var grown = new WasmValkeyLua(WASM, args -> "+OK\r\n".getBytes(UTF_8));
        var other = new WasmValkeyLua(WASM, args -> "+OK\r\n".getBytes(UTF_8));
        long initial = grown.memoryBytes();
        String random = "return math.random(1000) .. ' ' .. math.random(1000)";
        assertEquals(other.eval(random), grown.eval(random));
        assertEquals(result(true, integer(1L << 28)),
            grown.eval("local s = 'x' for i = 1, 28 do s = s .. s end return #s"));
        assertEquals(initial, grown.memoryBytes());
        assertEquals(other.eval(random), grown.eval(random));
    }

    @Test
    void deepReplyNestingPoisons() throws Exception {
        // Converting the reply recurses per nesting level, as Valkey does; on Chicory that is bounded by the JVM thread
        // stack (about 3650 levels at 1 MiB) before Valkey's own `reached lua stack limit` (about 8000) fires.
        var thrown = new AtomicReference<Throwable>();
        var after = new AtomicReference<Throwable>();
        var t = new Thread(null, () -> {
            var l = create(args -> "+OK\r\n".getBytes(UTF_8));
            try {
                l.eval("local t = {} for i = 1, 8000 do t = {t} end return t");
            } catch (Throwable e) {
                thrown.set(e);
            }
            try {
                l.eval("return 1");
            } catch (Throwable e) {
                after.set(e);
            }
        }, "eval-1m", 1024 * 1024);
        t.start();
        t.join();
        assertInstanceOf(com.dylibso.chicory.wasm.ChicoryException.class, thrown.get());
        assertInstanceOf(IllegalStateException.class, after.get());
    }

    @Test
    void replyTooLargeForMemoryCapPoisons() {
        // A 512 MiB string returned twice: building the reply runs out of Chicory's ~2 GiB memory. Valkey (no cap)
        // would reply; here the failed allocation must poison the instance, not write through a null pointer.
        var e = assertTimeoutPreemptively(java.time.Duration.ofSeconds(120), () -> assertThrows(IllegalStateException.class,
            () -> lua.eval("local s = 'x' for i = 1, 28 do s = s .. s end collectgarbage() return {s, s}")));
        assertEquals("reply conversion failed", e.getMessage());
    }

    @Test
    void numberCastSaturates() {
        // (long long) of an out-of-range double: wasm's i64.trunc_sat_f64_s saturates, NaN gives 0 (gaps.md).
        assertEquals(java.util.List.of(integer(Long.MAX_VALUE), integer(Long.MIN_VALUE), integer(Long.MAX_VALUE),
            integer(Long.MIN_VALUE), integer(0)), int64Edges());
    }
}
