package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.UUID;

/** One step in a membership's life (for the member's timeline and the churn figures). */
public record MembershipEvent(UUID id, UUID membershipId, String customerId, Type type, String tierCode, String actor,
                              String detail, Instant occurredAt) {

    /** What happened. ENDED, LAPSED and REFUNDED count as churn. */
    public enum Type {
        JOINED, TRIAL_STARTED, TRIAL_CONVERTED, RENEWED, RENEWAL_FAILED, PAST_DUE, LAPSED, CANCEL_SCHEDULED,
        CANCEL_RESUMED, UPGRADED, DOWNGRADE_SCHEDULED, DOWNGRADED, ENDED, FREE_DAYS_GRANTED, REFUNDED,
        CONVERTED_TO_PAID,
        /** The free trial ends in a few days (once per membership): the member is told what happens next. */
        TRIAL_ENDING
    }
}
