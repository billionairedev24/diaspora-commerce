package com.afrotransact.payments.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.DeliveryQuote;
import com.afrotransact.payments.domain.EffectiveFees;
import com.afrotransact.payments.domain.FeeKind;
import com.afrotransact.payments.domain.FeeSchedule;

/** "Fees & commissions": versioned commission per category, card fee and FBA fee with effective dates. */
public interface FeeScheduleService {

    /** Every version, newest first (history is never rewritten). */
    List<FeeSchedule> history();

    /** The version in force at {@code at} (null = now). */
    FeeSchedule activeAt(Instant at);

    /**
     * Product fees payments applies to {@code orgId} in {@code category} under the version in force at {@code at}
     * (null = now). Readable by calling services (payments:service, payments:fees.read) and staff.
     */
    default EffectiveFees effective(String category, String orgId, Instant at) {
        return effective(FeeKind.PRODUCT, category, orgId, at);
    }

    /**
     * The commission (PRODUCT) or connection fee (SERVICE) payments applies to {@code orgId} in {@code category} at
     * {@code at} (null = now), first match wins: an active negotiated rate (category, then {@code *}), the
     * organisation's plan rate, the category rate, the default. AfroTransact Retail pays none. Plus the card-fee
     * estimate and the FBA fee (products only).
     */
    EffectiveFees effective(FeeKind kind, String category, String orgId, Instant at);

    /**
     * Every rate {@code orgId} pays on {@code kind} lines at {@code at} (null = now), with the source of each and the
     * organisation's negotiated rates: staff fee readers and services, or the organisation's members who see payouts.
     */
    RateCard rateCard(String orgId, FeeKind kind, Instant at);

    /**
     * Prices one AfroTransact Delivery trip (S255) under the version in force at {@code at} (null = now): the
     * customer's delivery charge and the seller's pickup-delivery fee (override first; none for AfroTransact Retail).
     * Unavailable beyond the version's service radius or when it has no delivery pricing. orders-service
     * (payments:service) at checkout, services with payments:fees.read, staff readers.
     */
    DeliveryQuote quoteDelivery(String sellerOrgId, BigDecimal distanceMiles, int packages, Instant at);

    /** Super Admin / Finance (payments:configure): a new version for orders placed from its effective date. */
    FeeSchedule create(NewFeeScheduleCommand command);

    /**
     * Super Admin / Finance (payments:configure): withdraws a scheduled version before its effective date. The version
     * is kept and marked cancelled (who, when, why), never deleted or rewritten; it then never applies.
     */
    FeeSchedule cancel(UUID id, String reason);
}
