package valkeylua;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dylibso.chicory.runtime.ChicoryInterruptedException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WasmValkeyLuaTest extends ValkeyLuaTest {

    @Override
    ValkeyLua create(Handler handler, ServerInfo info) {
        return new WasmValkeyLua(handler, info);
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
    void instancesSharingCompiledModuleRunConcurrently() throws Exception {
        int n = 8;
        var pool = Executors.newFixedThreadPool(n);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < n; i++) {
                int id = i;
                tasks.add(() -> {
                    WasmValkeyLua l = new WasmValkeyLua(args -> (":" + id + "\r\n").getBytes(UTF_8));
                    for (int j = 0; j < 200; j++) {
                        assertEquals(integer(id * 1000 + j),
                            okOn(l, "return redis.call('get') * 1000 + #string.rep('a', " + j + ")"));
                        if (j == 100) l.reset();
                    }
                    return null;
                });
            }
            for (var f : pool.invokeAll(tasks)) f.get();
        } finally {
            pool.shutdown();
        }
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
        var grown = new WasmValkeyLua(args -> "+OK\r\n".getBytes(UTF_8));
        var other = new WasmValkeyLua(args -> "+OK\r\n".getBytes(UTF_8));
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
    void timeoutStopsInfiniteLoop() {
        assertTimesOut("while true do end");
    }

    @Test
    void timeoutStopsLongCCall() {
        assertTimesOut("return string.find(string.rep('a', 26), string.rep('a?', 26) .. string.rep('a', 26))");
    }

    /** A 200 ms timeout stops the script within 100 ms of its deadline; flag clear, instance usable without reset(). */
    private void assertTimesOut(String script) {
        long start = System.nanoTime();
        assertThrows(ScriptTimeoutException.class, () -> lua.eval(script, Duration.ofMillis(200)));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms >= 200 && ms < 300, "eval took " + ms + " ms");
        assertFalse(Thread.interrupted());
        assertEquals(result(true, integer(1)), lua.eval("return 1"));
    }

    @Test
    void timeoutDuringHandlerWaitsForItThenStopsBeforeMoreLua() {
        var calls = new AtomicInteger();
        var flagAtStart = new AtomicBoolean();
        var sleepInterrupted = new AtomicBoolean();
        var slept = new AtomicLong();
        var l = create(args -> {
            calls.incrementAndGet();
            flagAtStart.set(Thread.currentThread().isInterrupted());
            long t0 = System.nanoTime();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                sleepInterrupted.set(true);
            }
            slept.set((System.nanoTime() - t0) / 1_000_000);
            return "+PONG\r\n".getBytes(UTF_8);
        });
        assertThrows(ScriptTimeoutException.class,
            () -> l.eval("redis.call('ping') redis.call('ping') while true do end", Duration.ofMillis(100)));
        assertEquals(1, calls.get());
        assertFalse(flagAtStart.get());
        assertFalse(sleepInterrupted.get());
        assertTrue(slept.get() >= 300, "slept " + slept.get() + " ms");
        assertFalse(Thread.interrupted());
    }

    @Test
    void timeoutRaceNeverLeavesOrSendsAnInterrupt() throws Exception {
        // The loop length tracks the deadline (shorter after a timeout, longer after a success), so evals keep finishing
        // just before or just after it: the window where the watchdog races the end of Lua.
        long n = 10_000;
        int timedOut = 0;
        for (int i = 0; i < 10_000; i++) {
            try {
                lua.eval("for i = 1, tonumber(ARGV[1]) do end", List.of(), List.of(Long.toString(n).getBytes(UTF_8)),
                    Duration.ofMillis(1));
                n += n / 20 + 1;
            } catch (ScriptTimeoutException e) {
                timedOut++;
                n -= n / 20;
            }
            assertFalse(Thread.currentThread().isInterrupted(), "flag set after eval " + i);
            Thread.sleep(1);
        }
        assertTrue(timedOut > 1000 && timedOut < 9000, timedOut + " of 10000 timed out");
    }

    @Test
    void otherInterruptDuringTimedEvalIsScriptInterrupted() throws Exception {
        var evalThread = Thread.currentThread();
        var t = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
            evalThread.interrupt();
        });
        t.start();
        assertThrows(ScriptInterruptedException.class, () -> lua.eval("while true do end", Duration.ofSeconds(5)));
        assertTrue(Thread.interrupted());
        t.join();
    }

    @Test
    void timeoutWinsOverOtherInterruptWhichStaysSet() {
        // The handler interrupts its thread after the deadline passed: both stop the script, the timeout is reported.
        var l = create(args -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            Thread.currentThread().interrupt();
            return "+OK\r\n".getBytes(UTF_8);
        });
        try {
            assertThrows(ScriptTimeoutException.class,
                () -> l.eval("redis.call('ping') while true do end", Duration.ofMillis(100)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(result(true, integer(1)), l.eval("return 1"));
    }

    @Test
    void timedEvalThatFinishesLeavesFlagUnchanged() {
        assertEquals(result(true, integer(1)), lua.eval("return 1", Duration.ofSeconds(1)));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void timedEvalWithFlagSetOnEntryIsInterruptedAsUntimed() {
        // Chicory stops even `return 1` when the flag is already set; the timeout must not change that or the flag.
        var untimed = create(args -> "+OK\r\n".getBytes(UTF_8));
        Thread.currentThread().interrupt();
        try {
            assertThrows(ScriptInterruptedException.class, () -> untimed.eval("return 1"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertThrows(ScriptInterruptedException.class, () -> lua.eval("return 1", Duration.ofSeconds(1)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void suppliedWatchdogIsUsedAndNoThreadStarted() throws Exception {
        // In a child JVM: here earlier tests may already have started the shared watchdog thread.
        var p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), WasmValkeyLuaTest.class.getName())
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), UTF_8);
        assertEquals(0, p.waitFor(), out);
        assertTrue(out.contains("TIMED OUT, checks 1, shared watchdog false"), out);
    }

    /** The child JVM of suppliedWatchdogIsUsedAndNoThreadStarted. */
    public static void main(String[] args) {
        var watchdog = new ScheduledThreadPoolExecutor(1);
        WasmValkeyLua l = new WasmValkeyLua(a -> "+OK\r\n".getBytes(UTF_8), ServerInfo.DEFAULT,
            WasmValkeyLua.DEFAULT_MAX_RETAINED_BYTES, watchdog);
        try {
            l.eval("while true do end", Duration.ofMillis(50));
        } catch (ScriptTimeoutException e) {
            boolean shared = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("valkey-lua-watchdog"));
            System.out.println("TIMED OUT, checks " + watchdog.getCompletedTaskCount() + ", shared watchdog " + shared);
        }
        watchdog.shutdown();
    }
}
