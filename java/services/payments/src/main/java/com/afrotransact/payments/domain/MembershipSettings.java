package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Billing rules staff set in Settings, Membership (each change with a reason).
 *
 * @param refundWindowDays a member who cancels within this many days of a charge, having used no benefit since, gets the
 *                         fee back to the card; 0 turns the automatic refund off
 * @param retryDays        days after a failed renewal to try again; when the last retry fails the membership lapses
 */
public record MembershipSettings(int refundWindowDays, List<Integer> retryDays, String updatedBy, Instant updatedAt,
                                 long version) {

    public static final int MAX_RETRIES = 6;

    public MembershipSettings {
        retryDays = retryDays == null ? List.of() : List.copyOf(retryDays);
        Map<String, String> errors = new LinkedHashMap<>();
        if (refundWindowDays < 0 || refundWindowDays > 60) {
            errors.put("refundWindowDays", "Between 0 and 60 days");
        }
        if (retryDays.size() > MAX_RETRIES || retryDays.stream().anyMatch(d -> d == null || d < 1 || d > 30)) {
            errors.put("retryDays", "Up to " + MAX_RETRIES + " retries, each 1 to 30 days after the failure");
        }
        if (!errors.isEmpty()) {
            throw new PaymentsRuleViolation("payments.membership-settings-invalid", "Invalid membership settings",
                    errors);
        }
    }
}
