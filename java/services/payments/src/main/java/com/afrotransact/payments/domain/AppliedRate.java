package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The commission (products) or connection fee (services) one line pays, resolved once when the payment is created and
 * snapshotted on the line, so later changes to plans, negotiated rates or the fee schedule never re-price it.
 *
 * @param bps              basis points of the line's items amount (1000 = 10 %)
 * @param source           which level of the precedence matched
 * @param category         the category the rate was resolved for (lower case)
 * @param planCode         the organisation's plan when the rate was resolved (null when unknown or none)
 * @param negotiatedRateId the {@link NegotiatedRate} that applied (source OVERRIDE), null otherwise
 * @param negotiatedUntil  when that negotiated rate ends (null = open-ended or not negotiated)
 */
public record AppliedRate(FeeKind kind, int bps, RateSource source, String category, String planCode,
                          UUID negotiatedRateId, Instant negotiatedUntil) {

    public AppliedRate {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
        FeeSchedule.requireBps(bps, "applied rate");
        category = category == null ? "" : category;
    }

    /** AfroTransact Retail: nothing is charged. */
    public static AppliedRate retail(FeeKind kind, String category) {
        return new AppliedRate(kind, 0, RateSource.RETAIL, category, null, null, null);
    }
}
