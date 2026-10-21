package valkeylua;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
