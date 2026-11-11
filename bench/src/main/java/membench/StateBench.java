package membench;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import valkeylua.ValkeyLua;

/**
 * Eval time of a compiled script on each backend, each running all its evals in one long-lived lua_State.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsAppend = "-Xmx4g")
public class StateBench {

    static final List<byte[]> KEYS2 =
            List.of("{1}:a".getBytes(StandardCharsets.UTF_8), "{1}:b".getBytes(StandardCharsets.UTF_8));

    @Param({"wasm", "jni"})
    String backend;

    // noop: setup only. incr2: Patina's AWS EVAL benchmark script. small/large: CompileBench's rate limiter and its
    // 16 KB BullMQ-sized variant. calls: 100 redis.calls. add: 10,000 additions in Lua.
    @Param({"noop", "incr2", "small", "large", "calls", "add"})
    String shape;

    ValkeyLua lua;
    ValkeyLua.Script compiled;
    List<byte[]> keys;

    @Setup(Level.Trial)
    public void setup() {
        lua = Scenarios.create(backend);
        keys = Scenarios.KEYS;
        String script = switch (shape) {
            case "noop" -> "return 1";
            case "incr2" -> {
                keys = KEYS2;
                yield "redis.call('INCR',KEYS[1]) return redis.call('INCR',KEYS[2])";
            }
            case "small" -> CompileBench.SMALL;
            case "large" -> CompileBench.LARGE;
            case "calls" -> Scenarios.script("calls");
            case "add" -> "local s = 0 for i = 1, 10000 do s = s + i end return s";
            default -> throw new IllegalArgumentException(shape);
        };
        compiled = lua.compile(script);
        evalCompiled();
    }

    @Benchmark
    public ValkeyLua.Result evalCompiled() {
        var r = lua.eval(compiled, keys, Scenarios.ARGS);
        if (!r.ok()) throw new IllegalStateException(r.toString());
        return r;
    }
}
