package valkeylua;

/**
 * Thrown by {@link WasmValkeyLua#eval} when the eval stopped because its thread was interrupted; the cause is Chicory's
 * exception. The instance is poisoned, as after any escape. The library neither clears nor sets the interrupt flag, so
 * it is left as Chicory left it (set): the embedder owns it.
 */
public class ScriptInterruptedException extends RuntimeException {
    ScriptInterruptedException(Throwable cause) {
        super("script interrupted", cause);
    }
}
