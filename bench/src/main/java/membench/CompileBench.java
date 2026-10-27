package membench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import valkeylua.ValkeyLua;

/** Eval time of scripts sized like real Redis scripts, from source and pre-compiled (ValkeyLua.compile). */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 3, jvmArgsAppend = "-Xmx4g")
public class CompileBench {

    // A token-bucket rate limiter: ~0.5 KB, typical of hand-written EVAL scripts.
    static final String SMALL = """
            local key = KEYS[1]
            local rate = tonumber(ARGV[2] or '10')
            local burst = tonumber(ARGV[3] or '20')
            local now = tonumber(ARGV[4] or '1000')
            local state = redis.call('GET', key)
            local tokens, ts = burst, now
            if state and #state > 0 then
              local sep = string.find(state, ':', 1, true)
              if sep then
                tokens = tonumber(string.sub(state, 1, sep - 1)) or burst
                ts = tonumber(string.sub(state, sep + 1)) or now
              end
            end
            tokens = math.min(burst, tokens + (now - ts) * rate)
            local allowed = 0
            if tokens >= 1 then tokens = tokens - 1 allowed = 1 end
            redis.call('SET', key, tokens .. ':' .. now)
            return {allowed, math.floor(tokens)}
            """;

    // SMALL plus helper functions it never calls, as libraries like BullMQ inline into each script: ~16 KB.
    static final String LARGE;

    static {
        var sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("""
                    local function helper%d(key, jobId, opts)
                      local jobKey = key .. ':' .. jobId
                      local attempts = tonumber(redis.call('HGET', jobKey, 'atm') or '0') + 1
                      if opts and opts.limit and attempts > opts.limit then
                        redis.call('ZADD', key .. ':failed', attempts, jobId)
                        return {err = 'too many attempts'}
                      end
                      local data = cjson.decode(redis.call('HGET', jobKey, 'data') or '{}')
                      data.attempt = attempts
                      redis.call('HSET', jobKey, 'atm', attempts, 'data', cjson.encode(data))
                      return attempts
                    end
                    """.formatted(i));
        }
        LARGE = sb + SMALL;
    }

    @Param({"wasm", "jni"})
    String backend;

    @Param({"noop", "small", "large"})
    String size;

    ValkeyLua lua;
    String script;
    ValkeyLua.Script compiled;

    @Setup(Level.Trial)
    public void setup() {
        lua = Scenarios.create(backend);
        script = switch (size) {
            case "noop" -> "return 1";
            case "small" -> SMALL;
            default -> LARGE;
        };
        Scenarios.eval(lua, script);
        compiled = lua.compile(script);
    }

    @Benchmark
    public ValkeyLua.Result eval() {
        return Scenarios.eval(lua, script);
    }

    @Benchmark
    public ValkeyLua.Result evalCompiled() {
        var r = lua.eval(compiled, Scenarios.KEYS, Scenarios.ARGS);
        if (!r.ok()) throw new IllegalStateException(r.toString());
        return r;
    }

    @Benchmark
    public ValkeyLua.Script compile() {
        return lua.compile(script);
    }
}
