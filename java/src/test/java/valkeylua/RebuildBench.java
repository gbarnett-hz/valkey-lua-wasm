package valkeylua;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/** Cost of rebuilding the Wasm instance before an eval. Run from java/ (see README). */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 3, jvmArgs = "-Xmx4g")
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
public class RebuildBench {

    static final Handler HANDLER = args -> "$3\r\nbar\r\n".getBytes(UTF_8);

    @Param({"return 1", "return server.call('GET', KEYS[1])"})
    public String script;

    WasmValkeyLua lua;

    @Setup(Level.Trial)
    public void setup() {
        lua = new WasmValkeyLua(HANDLER);
    }

    @Benchmark
    public ValkeyLua.Result eval() {
        return lua.eval(script, List.of("foo".getBytes(UTF_8)), List.of());
    }

    @Benchmark
    public ValkeyLua.Result rebuildThenEval() {
        lua.rebuild();
        return lua.eval(script, List.of("foo".getBytes(UTF_8)), List.of());
    }
}
