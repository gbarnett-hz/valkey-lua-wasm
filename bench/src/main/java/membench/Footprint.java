package membench;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.management.ObjectName;
import com.dylibso.chicory.runtime.Instance;
import valkeylua.ValkeyLua;
import valkeylua.WasmValkeyLua;

/**
 * One fork of the footprint probe: Footprint <backend> <scenario> <instances> <evals>. Prints one line of
 * key=value pairs. Every sample is taken after two full GCs; deltas are against the same process before any
 * instance existed, so the JVM's own baseline cancels out.
 */
public final class Footprint {

    public static void main(String[] a) throws Exception {
        String backend = a[0], scenario = a[1];
        int instances = Integer.parseInt(a[2]), evals = Integer.parseInt(a[3]);
        String script = Scenarios.script(scenario);

        var out = new LinkedHashMap<String, Object>();
        out.put("backend", backend);
        out.put("scenario", scenario);
        out.put("instances", instances);

        var base = sample(List.of());
        var lua = new ArrayList<ValkeyLua>();
        for (int i = 0; i < instances; i++) lua.add(Scenarios.create(backend));
        var created = sample(lua);
        for (int e = 0; e < evals; e++) for (var l : lua) Scenarios.eval(l, script);
        var ran = sample(lua);

        for (var k : base.keySet()) {
            out.put("create." + k, created.get(k) - base.get(k));
            out.put("ran." + k, ran.get(k) - base.get(k));
        }
        out.put("ran.wasmPages", ran.get("wasmPages"));
        out.put("peakDelta", footprint().get("peak") - base.get("phys"));
        // Lua's own heap (collectgarbage 'count', KiB) at the end of the scenario, with its result still live.
        String probe = "local r = (function() " + script + " end)() return tostring(collectgarbage('count'))";
        out.put("luaHeapKiB", new String(Scenarios.eval(lua.get(0), probe).reply()).split("\r\n")[1]);

        var sb = new StringBuilder();
        out.forEach((k, v) -> sb.append(k).append('=').append(v).append(' '));
        System.out.println("RESULT " + sb.toString().trim());
    }

    static Map<String, Long> sample(List<ValkeyLua> keep) throws Exception {
        for (int i = 0; i < 2; i++) {
            System.gc();
            Thread.sleep(200);
        }
        var m = new LinkedHashMap<String, Long>();
        var mem = ManagementFactory.getMemoryMXBean();
        m.put("heapUsed", mem.getHeapMemoryUsage().getUsed());
        long meta = 0, code = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getName().equals("Metaspace")) meta += p.getUsage().getUsed();
            if (p.getName().startsWith("CodeHeap")) code += p.getUsage().getUsed();
        }
        m.put("metaspace", meta);
        m.put("codeCache", code);
        m.put("nmtCommitted", nmtCommitted());
        var fp = footprint();
        m.put("phys", fp.get("phys"));
        m.put("malloc", fp.get("malloc"));
        m.put("wasmPages", wasmPages(keep));
        java.lang.ref.Reference.reachabilityFence(keep);
        return m;
    }

    static long wasmPages(List<ValkeyLua> keep) throws Exception {
        long pages = 0;
        Field f = WasmValkeyLua.class.getDeclaredField("instance");
        f.setAccessible(true);
        for (var l : keep) if (l instanceof WasmValkeyLua) pages += ((Instance) f.get(l)).memory().pages();
        return pages;
    }

    static final Pattern NMT_TOTAL = Pattern.compile("Total: reserved=(\\d+)KB, committed=(\\d+)KB");

    static long nmtCommitted() throws Exception {
        String s = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"), "vmNativeMemory",
                new Object[] {new String[] {"summary"}}, new String[] {String[].class.getName()});
        var m = NMT_TOTAL.matcher(s);
        if (!m.find()) throw new IllegalStateException("NMT not enabled: " + s);
        return Long.parseLong(m.group(2)) * 1024;
    }

    static final Pattern PHYS = Pattern.compile("phys_footprint: (\\d+) B");
    static final Pattern PEAK = Pattern.compile("phys_footprint_peak: (\\d+) B");
    static final Pattern MALLOC = Pattern.compile("^\\s*(\\d+) B\\s+\\d+ B\\s+\\d+ B\\s+\\d+\\s+Malloc", Pattern.MULTILINE);

    /** macOS footprint(1) of this process: phys_footprint (what Activity Monitor shows), its peak, and dirty malloc. */
    static Map<String, Long> footprint() throws Exception {
        var p = new ProcessBuilder("footprint", "-f", "bytes", "-p", Long.toString(ProcessHandle.current().pid()))
                .redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        var m = new LinkedHashMap<String, Long>();
        var x = PHYS.matcher(s);
        if (!x.find()) throw new IllegalStateException(s);
        m.put("phys", Long.parseLong(x.group(1)));
        x = PEAK.matcher(s);
        x.find();
        m.put("peak", Long.parseLong(x.group(1)));
        long malloc = 0;
        for (x = MALLOC.matcher(s); x.find(); ) malloc += Long.parseLong(x.group(1));
        m.put("malloc", malloc);
        return m;
    }
}
