package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Compliance payout hold of an organisation, owned by seller-service (Stripe requirements past due, staff hold or a
 * disconnected account). While {@code paused}, payouts are neither requested nor approved; funds keep accruing.
 *
 * @param reason    AUTO_PAST_DUE, MANUAL, DEAUTHORIZED, CHARGES_DISABLED or CLEARED
 * @param changedAt when seller-service decided it (event occurred-at); older decisions never override newer ones
 */
public record PayoutHold(String orgId, String accountId, boolean paused, String reason, String actorId, String note,
                         Instant changedAt) {

    public PayoutHold {
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(changedAt, "changedAt");
    }
}
