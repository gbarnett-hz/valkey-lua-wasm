package valkeylua;

import java.time.Duration;

/**
 * Thrown by {@link WasmValkeyLua#eval(String, java.util.List, java.util.List, Duration)} when the timeout passed before
 * the script finished. The instance has been reset (only math.random's state carries over) and is usable. The interrupt
 * flag is as it would have been without the timeout. Handler side effects of the stopped script are not undone.
 */
public class ScriptTimeoutException extends RuntimeException {
    ScriptTimeoutException(Duration timeout) {
        super("script timed out after " + timeout.toMillis() + " ms");
    }
}
