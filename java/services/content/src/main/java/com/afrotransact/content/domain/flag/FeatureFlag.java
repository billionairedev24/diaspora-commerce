package com.afrotransact.content.domain.flag;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * A boolean feature flag.
 *
 * @param key               flag key ({@code checkout.express-pay})
 * @param description       what it controls
 * @param enabled           master on/off
 * @param rolloutPercentage share of subjects (0–100, two decimals) that get it, by stable bucketing
 * @param killSwitch        forces the flag off everywhere, overriding everything else
 * @param targeting         market, role and allow-list rules
 * @param version           optimistic lock, also part of evaluation ETags
 * @param updatedAt         last change
 * @param updatedBy         who changed it
 */
public record FeatureFlag(String key, String description, boolean enabled, double rolloutPercentage,
                          boolean killSwitch, FlagTargeting targeting, long version, Instant updatedAt,
                          String updatedBy) {

    private static final Pattern KEY = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,63}$");

    public FeatureFlag {
        targeting = targeting == null ? FlagTargeting.none() : targeting;
    }

    public void validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (key == null || !KEY.matcher(key).matches()) {
            errors.put("key", "Lower-case letters, digits, dots, dashes and underscores (2-64 characters)");
        }
        if (description == null || description.isBlank() || description.length() > 500) {
            errors.put("description", "Describe what the flag controls (up to 500 characters)");
        }
        if (Double.isNaN(rolloutPercentage) || rolloutPercentage < 0 || rolloutPercentage > 100) {
            errors.put("rolloutPercentage", "Between 0 and 100");
        }
        ContentRuleViolation.throwIfAny("content.invalid-flag", "The flag is invalid", errors);
    }

    public FeatureFlag withKillSwitch(boolean on, String actor, Instant now) {
        return new FeatureFlag(key, description, enabled, rolloutPercentage, on, targeting, version, now, actor);
    }

    /** Rollout in basis points of the 0–9999 bucket space. */
    public int rolloutBasisPoints() {
        return (int) Math.round(rolloutPercentage * 100);
    }
}
