package com.afrotransact.identity.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounds for a sign-out policy; a violation is a 422 with one message per field. */
public final class SessionPolicyRules {

    public static final int IDLE_MIN = 1;
    public static final int IDLE_MAX = 180 * 24 * 60;
    public static final int ABSOLUTE_MIN = 5;
    public static final int ABSOLUTE_MAX = 365 * 24 * 60;
    public static final int WARN_MIN = 0;
    public static final int WARN_MAX = 300;

    private SessionPolicyRules() {
    }

    /** Validates the stored overrides together with the policy they produce (idle may not exceed absolute). */
    public static void validate(SessionAudience audience, SessionPolicyOverrides o, SessionPolicy effective) {
        Map<String, String> errors = new LinkedHashMap<>();
        range(errors, "idleTimeoutMinutes", o.idleTimeoutMinutes(), IDLE_MIN, IDLE_MAX, "minutes");
        range(errors, "absoluteTimeoutMinutes", o.absoluteTimeoutMinutes(), ABSOLUTE_MIN, ABSOLUTE_MAX, "minutes");
        range(errors, "warnSeconds", o.warnSeconds(), WARN_MIN, WARN_MAX, "seconds");
        if (errors.isEmpty()) {
            if (effective.idleTimeoutMinutes() > effective.absoluteTimeoutMinutes()) {
                errors.put("idleTimeoutMinutes", "The idle timeout cannot be longer than the session length");
            }
            if (effective.warnSeconds() > 0 && effective.warnSeconds() >= effective.idleTimeoutMinutes() * 60) {
                errors.put("warnSeconds", "The warning must come before the idle timeout; use 0 for no warning");
            }
        }
        if (!errors.isEmpty()) {
            throw new IdentityRuleViolation("identity.invalid-session-policy",
                    "Check the highlighted fields for " + audience.label(), errors);
        }
    }

    private static void range(Map<String, String> errors, String field, Integer value, int min, int max, String unit) {
        if (value != null && (value < min || value > max)) {
            errors.put(field, "Between " + min + " and " + max + " " + unit);
        }
    }
}
