package com.afrotransact.orders.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service level, escalation, hand-out and amount rules of delivery quotes (the review workflow's settings, plus the
 * amount bounds and how long an agreed quote is valid).
 *
 * @param slaBusinessDays      how many business days (Monday to Friday) a request may wait for an answer (default 1)
 * @param dueSoonPercent       share of the wait after which it is "due soon" (default 75)
 * @param unassignedAfterHours a request nobody has taken for this long is escalated before its SLA ends (default 4)
 * @param level2AfterHours     hours after the SLA ended (or level 1) before level 2 is raised (default 24)
 * @param autoAssign           how a new request is given to a person
 * @param minAmountMinor       smallest amount staff may agree (minor units)
 * @param maxAmountMinor       largest amount staff may agree
 * @param validityHours        how long an agreed quote can be used (default 48)
 * @param requestsPerHour      how many requests one shopper may start per hour (rate limit)
 * @param duplicateWindowMinutes a repeat of the same cart and address inside this window returns the same request
 */
public record QuoteSettings(int slaBusinessDays, int dueSoonPercent, int unassignedAfterHours, int level2AfterHours,
                            AutoAssign autoAssign, String roundRobinCursor, QuoteTarget level1, QuoteTarget level2,
                            long minAmountMinor, long maxAmountMinor, int validityHours, int requestsPerHour,
                            int duplicateWindowMinutes, String updatedByName, Instant updatedAt) {

    public QuoteSettings {
        autoAssign = autoAssign == null ? AutoAssign.NONE : autoAssign;
        level1 = level1 == null ? QuoteTarget.NOBODY : level1;
        level2 = level2 == null ? QuoteTarget.NOBODY : level2;
    }

    /** How a new request is handed out. */
    public enum AutoAssign {
        NONE,
        ROUND_ROBIN,
        LEAST_LOADED
    }

    /** One escalation level: everyone holding a role plus named people. */
    public record QuoteTarget(String roleKey, List<String> userIds) {

        public static final QuoteTarget NOBODY = new QuoteTarget(null, List.of());

        public QuoteTarget {
            roleKey = roleKey == null || roleKey.isBlank() ? null : roleKey.trim();
            userIds = userIds == null ? List.of() : List.copyOf(userIds);
        }

        public boolean empty() {
            return roleKey == null && userIds.isEmpty();
        }
    }

    public QuoteTarget at(int level) {
        return level == 1 ? level1 : level2;
    }

    public Duration validity() {
        return Duration.ofHours(validityHours);
    }

    /** Field → what is wrong; empty when the settings are valid. */
    public Map<String, String> problems() {
        Map<String, String> p = new LinkedHashMap<>();
        range(p, "slaBusinessDays", slaBusinessDays, 1, 10, "Business days must be 1 to 10");
        range(p, "dueSoonPercent", dueSoonPercent, 1, 99, "Due soon must be 1 to 99 percent");
        range(p, "unassignedAfterHours", unassignedAfterHours, 1, 720, "Hours must be 1 to 720");
        range(p, "level2AfterHours", level2AfterHours, 1, 720, "Hours must be 1 to 720");
        range(p, "validityHours", validityHours, 1, 720, "Hours must be 1 to 720");
        range(p, "requestsPerHour", requestsPerHour, 1, 100, "Requests per hour must be 1 to 100");
        range(p, "duplicateWindowMinutes", duplicateWindowMinutes, 1, 1440, "Minutes must be 1 to 1440");
        if (minAmountMinor < 0) {
            p.put("minAmountMinor", "The smallest amount can't be negative");
        }
        if (maxAmountMinor < minAmountMinor) {
            p.put("maxAmountMinor", "The largest amount must be at least the smallest");
        }
        if (!level2.empty() && level1.empty()) {
            p.put("level2", "Set level 1 before level 2");
        }
        return p;
    }

    private static void range(Map<String, String> p, String field, int v, int min, int max, String message) {
        if (v < min || v > max) {
            p.put(field, message);
        }
    }

    public QuoteSettings withCursor(String cursor) {
        return new QuoteSettings(slaBusinessDays, dueSoonPercent, unassignedAfterHours, level2AfterHours, autoAssign,
                cursor, level1, level2, minAmountMinor, maxAmountMinor, validityHours, requestsPerHour,
                duplicateWindowMinutes, updatedByName, updatedAt);
    }
}
