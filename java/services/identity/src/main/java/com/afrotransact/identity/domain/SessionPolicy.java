package com.afrotransact.identity.domain;

import java.time.Duration;

/**
 * When one audience is signed out.
 *
 * @param idleTimeoutMinutes     sign out after this long without real user activity
 * @param absoluteTimeoutMinutes sign out this long after sign-in, however active
 * @param warnSeconds            clients warn this long before the idle timeout (0 = no warning)
 */
public record SessionPolicy(int idleTimeoutMinutes, int absoluteTimeoutMinutes, int warnSeconds) {

    /** What the stored overrides leave unset falls back to {@code defaults}. */
    public static SessionPolicy resolve(SessionPolicy defaults, SessionPolicyOverrides o) {
        return new SessionPolicy(
                o.idleTimeoutMinutes() != null ? o.idleTimeoutMinutes() : defaults.idleTimeoutMinutes(),
                o.absoluteTimeoutMinutes() != null ? o.absoluteTimeoutMinutes() : defaults.absoluteTimeoutMinutes(),
                o.warnSeconds() != null ? o.warnSeconds() : defaults.warnSeconds());
    }

    public Duration idleTimeout() {
        return Duration.ofMinutes(idleTimeoutMinutes);
    }

    public Duration absoluteTimeout() {
        return Duration.ofMinutes(absoluteTimeoutMinutes);
    }

    /** One line for the audit log. */
    public String auditSummary() {
        return "idle=" + idleTimeoutMinutes + "m absolute=" + absoluteTimeoutMinutes + "m warn=" + warnSeconds + "s";
    }
}
