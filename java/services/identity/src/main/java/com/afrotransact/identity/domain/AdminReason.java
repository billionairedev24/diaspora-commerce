package com.afrotransact.identity.domain;

import java.util.Map;

/** Security-sensitive admin actions carry a reason of 10 to 500 characters, kept in the access change log. */
public final class AdminReason {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 500;

    private AdminReason() {
    }

    /** Returns the trimmed reason; throws {@link IdentityRuleViolation} (422) when it is missing, short or long. */
    public static String require(String reason) {
        String r = reason == null ? "" : reason.trim();
        if (r.length() < MIN_LENGTH || r.length() > MAX_LENGTH) {
            throw new IdentityRuleViolation("identity.reason-required",
                    "Give a reason of " + MIN_LENGTH + " to " + MAX_LENGTH + " characters",
                    Map.of("reason", "Use " + MIN_LENGTH + " to " + MAX_LENGTH + " characters"));
        }
        return r;
    }
}
