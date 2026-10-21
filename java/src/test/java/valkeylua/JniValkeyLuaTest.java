package valkeylua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class JniValkeyLuaTest extends ValkeyLuaTest {

    @Override
    ValkeyLua create(Handler handler, ServerInfo info) {
        return new JniValkeyLua(handler, info);
    }

    @Test
    void numberCastFollowsHardware() {
        // (long long) of an out-of-range double is undefined in C: arm64 saturates (NaN gives 0), x86-64 gives INT64_MIN.
        var expected = System.getProperty("os.arch").equals("aarch64")
            ? java.util.List.of(integer(Long.MAX_VALUE), integer(Long.MIN_VALUE), integer(Long.MAX_VALUE),
                integer(Long.MIN_VALUE), integer(0))
            : java.util.Collections.nCopies(5, integer(Long.MIN_VALUE));
        assertEquals(expected, int64Edges());
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
