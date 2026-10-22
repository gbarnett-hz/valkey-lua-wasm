package membench;

import java.lang.management.ManagementFactory;
import javax.management.ObjectName;

/** Histo <backend> <scenario> <evals>: class histogram of everything on the heap (no forced GC) after the evals. */
public final class Histo {
    public static void main(String[] a) throws Exception {
        var lua = Scenarios.create(a[0]);
        String script = Scenarios.script(a[1]);
        for (int i = Integer.parseInt(a[2]); i > 0; i--) Scenarios.eval(lua, script);
        String s = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                new Object[] {new String[] {"-all"}}, new String[] {String[].class.getName()});
        s.lines().limit(25).forEach(System.out::println);
    }
}
