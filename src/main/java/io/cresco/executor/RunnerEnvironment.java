package io.cresco.executor;

import io.cresco.library.plugin.SecretParams;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What environment a runner's process gets (config {@code runner_env}). An allowlist, never a denylist: only variables
 * named here pass, whatever else the agent's environment holds ({@code CRESCO_*} settings and secrets, JVM option
 * variables, database passwords...).
 * <ul>
 *   <li>{@code scrubbed} (default): PATH, HOME, USER, LOGNAME, SHELL, LANG, LC_*, TZ, TMPDIR, TERM, and the names an
 *   admin adds with {@code runner_env_allow} (a comma list), except any name that is CRESCO_*, a JVM option variable or
 *   secret-named, which is never passed;</li>
 *   <li>{@code minimal}: PATH, HOME, LANG, LC_* and TZ only;</li>
 *   <li>{@code inherit}: the agent's whole environment, secrets included (logged as a WARNING).</li>
 * </ul>
 * This governs the runner's own environment and that of its launcher (its parent). It is not a boundary against the
 * agent's OS user reading the agent process itself: see the README, "What a runner can reach".
 */
final class RunnerEnvironment {

    static final String SCRUBBED = "scrubbed", MINIMAL = "minimal", INHERIT = "inherit";

    private static final Set<String> JVM_OPTION_VARS = Set.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS",
            "JAVA_OPTS");
    private static final Set<String> SCRUBBED_VARS = Set.of("PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "TZ",
            "TMPDIR", "TERM");
    private static final Set<String> MINIMAL_VARS = Set.of("PATH", "HOME", "LANG", "TZ");

    private RunnerEnvironment() {}

    /** The mode named by {@code configured}, or null when it names none. */
    static String mode(String configured) {
        if (configured == null) return SCRUBBED;
        String m = configured.trim().toLowerCase(Locale.ROOT);
        return (SCRUBBED.equals(m) || MINIMAL.equals(m) || INHERIT.equals(m)) ? m : null;
    }

    /** True when {@code name} may never be passed by an allowlist, whoever lists it. */
    static boolean forbidden(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("CRESCO_") || JVM_OPTION_VARS.contains(upper) || upper.endsWith("_OPTS")
                || upper.endsWith("PWD") && !upper.equals("PWD") || upper.endsWith("PASSWD")
                || SecretParams.SECRET_KEY.matcher(name).find();
    }

    /** The names {@code runner_env_allow} adds that may be passed (forbidden ones dropped). */
    static Set<String> extra(String configured) {
        Set<String> out = new HashSet<>();
        if (configured == null) return out;
        for (String n : configured.split(",")) {
            String t = n.trim();
            if (!t.isEmpty() && t.matches("[A-Za-z_][A-Za-z0-9_]*") && !forbidden(t)) out.add(t);
        }
        return out;
    }

    /** True when the variable {@code name} is kept under {@code mode} (with no extra names). */
    static boolean keeps(String mode, String name) {
        return keeps(mode, name, Set.of());
    }

    static boolean keeps(String mode, String name, Collection<String> extra) {
        if (INHERIT.equals(mode)) return true;
        String upper = name.toUpperCase(Locale.ROOT);
        if (MINIMAL.equals(mode)) return MINIMAL_VARS.contains(upper) || upper.startsWith("LC_");
        if (forbidden(name)) return false;
        return SCRUBBED_VARS.contains(upper) || upper.startsWith("LC_") || extra.contains(name);
    }

    /** Remove from {@code env} (a ProcessBuilder's) every variable {@code mode} does not keep. */
    static void apply(Map<String, String> env, String mode) {
        apply(env, mode, Set.of());
    }

    static void apply(Map<String, String> env, String mode, Collection<String> extra) {
        for (Iterator<String> it = env.keySet().iterator(); it.hasNext(); ) {
            if (!keeps(mode, it.next(), extra)) it.remove();
        }
    }
}
