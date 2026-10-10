package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The fees payments will apply to one organisation and category under one fee-schedule version, so callers (e.g.
 * bookings' take-home estimates) never hard-code them.
 *
 * @param commissionSource OVERRIDE (negotiated for the organisation), CATEGORY, DEFAULT or RETAIL (no commission)
 * @param fbaPerUnit       FBA fee per unit (zero for AfroTransact Retail)
 * @param retail           the organisation is AfroTransact Retail: no commission, no FBA fee, only its Stripe-fee
 *                         share
 */
public record EffectiveFees(UUID feeScheduleId, int version, Instant effectiveFrom, String currency, String category,
                            String orgId, int commissionBps, String commissionSource, int cardPctBps,
                            Money cardFixed, Money fbaPerUnit, boolean reverseCommissionOnRefund, boolean retail) {

    /** Card-fee estimate of one charge (percentage + fixed), as payments estimates it before Stripe reports. */
    public Money cardFeeOf(Money charge) {
        if (!charge.isPositive()) {
            return Money.zero(charge.currency());
        }
        return charge.basisPoints(cardPctBps).plus(cardFixed);
    }
}
