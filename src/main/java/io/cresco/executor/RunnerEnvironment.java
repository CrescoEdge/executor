package io.cresco.executor;

import io.cresco.library.plugin.SecretParams;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What environment a runner's process gets (config {@code runner_env}). A runner's command runs as the agent's OS user,
 * so it can read whatever that user can; the environment is the one thing it would otherwise be handed for free, and
 * the agent's environment carries its secrets ({@code CRESCO_*} variables are how an agent is given its settings and
 * secrets, and JVM option variables can carry {@code -D} secrets).
 * <ul>
 *   <li>{@code scrubbed} (default): the agent's environment without any {@code CRESCO_*} variable, any variable whose
 *   name looks like a secret (the controller's secret-name pattern: secret, password, passphrase, token, ..._key, pin),
 *   and the JVM option variables;</li>
 *   <li>{@code minimal}: only PATH, HOME, USER, LOGNAME, SHELL, LANG, LC_*, TZ, TMPDIR and TERM;</li>
 *   <li>{@code inherit}: the agent's whole environment, secrets included (the old behaviour; logged as a WARNING).</li>
 * </ul>
 */
final class RunnerEnvironment {

    static final String SCRUBBED = "scrubbed", MINIMAL = "minimal", INHERIT = "inherit";

    private static final Set<String> JVM_OPTION_VARS = Set.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS");
    private static final Set<String> MINIMAL_VARS = Set.of("PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "TZ",
            "TMPDIR", "TERM");

    private RunnerEnvironment() {}

    /** The mode named by {@code configured}, or null when it names none. */
    static String mode(String configured) {
        if (configured == null) return SCRUBBED;
        String m = configured.trim().toLowerCase(Locale.ROOT);
        return (SCRUBBED.equals(m) || MINIMAL.equals(m) || INHERIT.equals(m)) ? m : null;
    }

    /** True when the variable {@code name} is kept under {@code mode}. */
    static boolean keeps(String mode, String name) {
        if (INHERIT.equals(mode)) return true;
        String upper = name.toUpperCase(Locale.ROOT);
        if (MINIMAL.equals(mode)) return MINIMAL_VARS.contains(upper) || upper.startsWith("LC_");
        if (upper.startsWith("CRESCO_")) return false;
        if (JVM_OPTION_VARS.contains(upper)) return false;
        return !SecretParams.SECRET_KEY.matcher(name).find();
    }

    /** Remove from {@code env} (a ProcessBuilder's) every variable {@code mode} does not keep. */
    static void apply(Map<String, String> env, String mode) {
        for (Iterator<String> it = env.keySet().iterator(); it.hasNext(); ) {
            if (!keeps(mode, it.next())) it.remove();
        }
    }
}
