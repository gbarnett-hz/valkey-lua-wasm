package valkeylua;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dylibso.chicory.runtime.ChicoryInterruptedException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WasmValkeyLuaTest extends ValkeyLuaTest {

    static final Path WASM = Path.of("../lua/lua.wasm");

    @Override
    ValkeyLua create(Handler handler, ServerInfo info) {
        return new WasmValkeyLua(WASM, handler, info);
    }

    @Test
    void resetRecoversPoisonedInstance() {
        assertThrows(IllegalStateException.class,
            () -> lua.eval("return setmetatable({err='x'}, {__index = function() error('boom') end})"));
        assertThrows(IllegalStateException.class, () -> lua.eval("return 1"));
        ((WasmValkeyLua) lua).reset();
        assertEquals(result(true, integer(1)), lua.eval("return 1"));
    }

    @Test
    void interruptStopsInfiniteLoop() throws Exception {
        assertInterruptStops("while true do end");
    }

    @Test
    void interruptStopsLongCCall() throws Exception {
        // About 4 s on Redis 8.8.0, all inside str_find's backtracking.
        assertInterruptStops("return string.find(string.rep('a', 26), string.rep('a?', 26) .. string.rep('a', 26))");
    }

    /** Interrupts the eval thread after 100 ms; eval must throw ScriptInterruptedException promptly, flag left set. */
    private void assertInterruptStops(String script) throws Exception {
        var thrown = new AtomicReference<Throwable>();
        var flagSet = new AtomicBoolean();
        var thrownAt = new AtomicLong();
        var t = new Thread(() -> {
            try {
                lua.eval(script);
            } catch (Throwable e) {
                thrownAt.set(System.nanoTime());
                thrown.set(e);
            }
            flagSet.set(Thread.currentThread().isInterrupted());
        });
        t.start();
        Thread.sleep(100);
        long interruptedAt = System.nanoTime();
        t.interrupt();
        t.join(5000);
        assertInstanceOf(ScriptInterruptedException.class, thrown.get());
        assertInstanceOf(ChicoryInterruptedException.class, thrown.get().getCause());
        assertTrue(flagSet.get());
        long ms = (thrownAt.get() - interruptedAt) / 1_000_000;
        assertTrue(ms < 50, "eval took " + ms + " ms to stop");
        assertPoisonedThenReset(lua);
    }

    private void assertPoisonedThenReset(ValkeyLua l) {
        assertThrows(IllegalStateException.class, () -> l.eval("return 1"));
        ((WasmValkeyLua) l).reset();
        assertEquals(result(true, integer(1)), l.eval("return 1"));
    }

    @Test
    void handlerInterruptingItsThreadStopsScript() throws Exception {
        var thrown = new AtomicReference<Throwable>();
        var flagSet = new AtomicBoolean();
        var l = create(args -> {
            Thread.currentThread().interrupt();
            return "+OK\r\n".getBytes(UTF_8);
        });
        var t = new Thread(() -> {
            try {
                l.eval("redis.call('ping') while true do end");
            } catch (Throwable e) {
                thrown.set(e);
            }
            flagSet.set(Thread.currentThread().isInterrupted());
        });
        t.start();
        t.join(5000);
        assertInstanceOf(ScriptInterruptedException.class, thrown.get());
        assertTrue(flagSet.get());
        assertPoisonedThenReset(l);
    }

    @Test
    void otherPoisoningIsNotAnInterrupt() {
        var e = assertThrows(RuntimeException.class,
            () -> lua.eval("return setmetatable({err='x'}, {__index = function() error('boom') end})"));
        assertEquals(IllegalStateException.class, e.getClass());
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
}
