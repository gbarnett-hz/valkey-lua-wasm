package valkeylua;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class JniValkeyLuaTest extends ValkeyLuaTest {

    @Override
    ValkeyLua create(Handler handler, ServerInfo info) {
        return new JniValkeyLua(handler, info);
    }

    @Test
    void recursionValkeyDoesNotLimitUsesValkeySizedStack() {
        // Lua runs on its own 8 MiB threads: these killed the JVM on 2 MiB caller stacks; Wasm poisons (gaps.md section 4).
        assertEquals(integer(1), ok("return string.find(string.rep('a', 20000), string.rep('a?', 20000))"));
        var r = lua.eval("local t = {} for i = 1, 10000 do t = {t} end return t");
        assertTrue(r.ok() && new String(r.reply(), ISO_8859_1).endsWith("*1\r\n-ERR reached lua stack limit\r\n"), r::toString);
    }

    @Test
    void timeoutIsUnsupported() {
        assertThrows(UnsupportedOperationException.class, () -> lua.eval("return 1", Duration.ofSeconds(1)));
        assertEquals(integer(1), ok("return 1"));
    }

    @Test
    void replyLargerThanJavaHeapThrows() throws Exception {
        // A reply that doesn't fit the Java heap must throw OutOfMemoryError (poisoning the instance), not crash the
        // JVM. Run in a child JVM with a small heap, since the failure mode is a crash.
        var p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx64m",
                "-cp", System.getProperty("java.class.path"), JniValkeyLuaTest.class.getName())
            .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        assertTrue(out.contains("THROWN java.lang.OutOfMemoryError"), out);
    }

    /** The child JVM of replyLargerThanJavaHeapThrows. */
    public static void main(String[] args) {
        try {
            new JniValkeyLua(a -> "+OK\r\n".getBytes()).eval("return string.rep('x', 128 * 1024 * 1024)");
            System.out.println("RETURNED");
        } catch (Throwable e) {
            System.out.println("THROWN " + e.getClass().getName());
        }
    }
}
