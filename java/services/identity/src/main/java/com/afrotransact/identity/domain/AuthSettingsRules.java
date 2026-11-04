package com.afrotransact.identity.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounds for the authentication settings; a violation is a 422 with one message per field. */
public final class AuthSettingsRules {

    public static final int PASSWORD_MIN = 8;
    public static final int PASSWORD_MAX = 64;
    public static final int IDLE_MIN = 5;
    public static final int IDLE_MAX = 24 * 60;
    public static final int ABSOLUTE_MIN = 60;
    public static final int ABSOLUTE_MAX = 30 * 24 * 60;
    public static final int ACCESS_MIN = 1;
    public static final int ACCESS_MAX = 60;
    public static final int REFRESH_MIN = 5;
    public static final int REFRESH_MAX = 90 * 24 * 60;

    private AuthSettingsRules() {
    }

    /** Validates the stored overrides together with the settings they produce (idle may not exceed absolute). */
    public static void validate(AuthSettingsOverrides o, AuthSettings effective) {
        Map<String, String> errors = new LinkedHashMap<>();
        range(errors, "passwordMinLength", o.passwordMinLength(), PASSWORD_MIN, PASSWORD_MAX, "characters");
        range(errors, "sessionIdleTimeoutMinutes", o.sessionIdleTimeoutMinutes(), IDLE_MIN, IDLE_MAX, "minutes");
        range(errors, "sessionAbsoluteTimeoutMinutes", o.sessionAbsoluteTimeoutMinutes(), ABSOLUTE_MIN, ABSOLUTE_MAX,
                "minutes");
        range(errors, "accessTokenTtlCapMinutes", o.accessTokenTtlCapMinutes(), ACCESS_MIN, ACCESS_MAX, "minutes");
        range(errors, "refreshTokenTtlCapMinutes", o.refreshTokenTtlCapMinutes(), REFRESH_MIN, REFRESH_MAX, "minutes");
        if (errors.isEmpty() && effective.sessionIdleTimeoutMinutes() > effective.sessionAbsoluteTimeoutMinutes()) {
            errors.put("sessionIdleTimeoutMinutes", "The idle timeout cannot be longer than the absolute timeout");
        }
        if (!errors.isEmpty()) {
            throw new IdentityRuleViolation("identity.invalid-auth-settings", "Check the highlighted fields", errors);
        }
    }

    private static void range(Map<String, String> errors, String field, Integer value, int min, int max, String unit) {
        if (value != null && (value < min || value > max)) {
            errors.put(field, "Between " + min + " and " + max + " " + unit);
        }
    }
}
