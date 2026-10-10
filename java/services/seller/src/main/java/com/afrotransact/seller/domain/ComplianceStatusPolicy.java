package com.afrotransact.seller.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Past due or disabled → RESTRICTED; currently due with ≤ 3 days to the deadline → RESTRICTED_SOON, otherwise
 * ACTION_NEEDED; only pending verification → VERIFYING; nothing open → COMPLIANT.
 */
public final class ComplianceStatusPolicy {

    public static final Duration RESTRICTED_SOON_WITHIN = Duration.ofDays(3);

    private ComplianceStatusPolicy() {
    }

    public static ComplianceStatus derive(RequirementsSnapshot s, Instant now) {
        if (!s.pastDue().isEmpty() || (s.disabledReason() != null && !s.disabledReason().isBlank())) {
            return ComplianceStatus.RESTRICTED;
        }
        if (!s.currentlyDue().isEmpty()) {
            boolean soon = s.currentDeadline() != null
                    && Duration.between(now, s.currentDeadline()).compareTo(RESTRICTED_SOON_WITHIN) <= 0;
            return soon ? ComplianceStatus.RESTRICTED_SOON : ComplianceStatus.ACTION_NEEDED;
        }
        if (!s.pendingVerification().isEmpty()) {
            return ComplianceStatus.VERIFYING;
        }
        return ComplianceStatus.COMPLIANT;
    }
}
