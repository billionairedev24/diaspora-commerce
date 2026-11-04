package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The fees payments will apply to one organisation, kind and category under one fee-schedule version, so callers
 * (e.g. bookings' take-home estimates, Seller Center's fees page) never hard-code them.
 *
 * @param commissionBps    the take rate of {@code kind}: the commission of a product line, the connection fee of a
 *                         service line
 * @param commissionSource OVERRIDE (negotiated for the organisation), PLAN, CATEGORY, DEFAULT or RETAIL (nothing)
 * @param fbaPerUnit       FBA fee per unit (zero for AfroTransact Retail and for services)
 * @param retail           the organisation is AfroTransact Retail: no commission, no FBA fee, only its Stripe-fee
 *                         share
 * @param planCode         the organisation's plan used for the rate (null when none or unknown)
 * @param negotiatedRateId the negotiated rate that applies (source OVERRIDE from a negotiated rate), else null
 * @param negotiatedUntil  when that negotiated rate ends (null = open-ended)
 */
public record EffectiveFees(UUID feeScheduleId, int version, Instant effectiveFrom, String currency, String category,
                            String orgId, int commissionBps, String commissionSource, int cardPctBps,
                            Money cardFixed, Money fbaPerUnit, boolean reverseCommissionOnRefund, boolean retail,
                            FeeKind kind, String planCode, UUID negotiatedRateId, Instant negotiatedUntil) {

    public EffectiveFees {
        kind = kind == null ? FeeKind.PRODUCT : kind;
    }

    /** Product fees without plan or negotiated-rate details. */
    public EffectiveFees(UUID feeScheduleId, int version, Instant effectiveFrom, String currency, String category,
                         String orgId, int commissionBps, String commissionSource, int cardPctBps, Money cardFixed,
                         Money fbaPerUnit, boolean reverseCommissionOnRefund, boolean retail) {
        this(feeScheduleId, version, effectiveFrom, currency, category, orgId, commissionBps, commissionSource,
                cardPctBps, cardFixed, fbaPerUnit, reverseCommissionOnRefund, retail, FeeKind.PRODUCT, null, null,
                null);
    }

    /** Card-fee estimate of one charge (percentage + fixed), as payments estimates it before Stripe reports. */
    public Money cardFeeOf(Money charge) {
        if (!charge.isPositive()) {
            return Money.zero(charge.currency());
        }
        return charge.basisPoints(cardPctBps).plus(cardFixed);
    }
}
