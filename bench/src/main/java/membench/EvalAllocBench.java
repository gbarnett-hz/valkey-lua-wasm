package membench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import valkeylua.ValkeyLua;

/** Java-heap bytes allocated per eval (run with -prof gc, read gc.alloc.rate.norm). */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 3, jvmArgsAppend = "-Xmx4g")
public class EvalAllocBench {

    @Param({"wasm", "jni"})
    String backend;

    @Param({"noop", "calls", "json", "reply", "bigstr"})
    String scenario;

    ValkeyLua lua;
    String script;

    @Setup(Level.Trial)
    public void setup() {
        lua = Scenarios.create(backend);
        script = Scenarios.script(scenario);
    }

    @Benchmark
    public ValkeyLua.Result eval() {
        return Scenarios.eval(lua, script);
    }
}
