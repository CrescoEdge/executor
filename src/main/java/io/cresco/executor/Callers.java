package io.cresco.executor;

import io.cresco.library.messaging.MsgEvent;
import io.cresco.library.security.Caller;

/**
 * The verified caller of a message, as infrastructure stamped it (controller docs/caller-authorization.md). Never the
 * message's own source fields: those are written by the sender. A message with no stamp is anonymous only in explicit
 * insecure development mode ({@code broker_security_enabled=false} on the agent); otherwise it has no caller and
 * every decision that needs one refuses. A malformed stamp is never trusted.
 */
final class Callers {

    private Callers() {}

    /** True when the agent was started with {@code broker_security_enabled=false} (-D or CRESCO_ environment). */
    static boolean insecureMode() {
        return "false".equalsIgnoreCase(System.getProperty("broker_security_enabled"))
                || "false".equalsIgnoreCase(System.getenv("CRESCO_broker_security_enabled"));
    }

    /** The verified caller; with no stamp, anonymous in insecure mode, else null. */
    static Caller of(MsgEvent m, boolean insecure) {
        if (m == null) return null;
        Caller c = Caller.fromMsgEvent(m);
        if (c != null) return c;
        if (Caller.hasAny(m)) return null;
        return insecure ? Caller.anonymous() : null;
    }

    static boolean hasRole(Caller c, String role) {
        return c != null && c.hasRole(role);
    }
}
