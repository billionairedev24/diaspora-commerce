package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.MembershipTier;
import com.afrotransact.payments.domain.TierBenefit;
import com.afrotransact.payments.domain.TierStatus;
import com.afrotransact.payments.port.MembershipTierRepository;

/**
 * The membership tiers staff define in Settings (S323): price per month and per year, trial days, sort order, publish
 * and retire, and which benefits each tier has from the menu. Every change carries a reason and is kept in an audit
 * history. Members keep the price they signed up at; benefit changes apply to every member at once.
 */
public interface MembershipTierService {

    /** The tiers a shopper can join, entry tier first (any signed-in caller). */
    List<MembershipTier> published();

    /** Every tier with how many members it has (staff with payments:read). */
    List<TierOverview> overview();

    /** A new tier, as a draft or published at once (payments:configure; a reason). */
    MembershipTier create(SaveTier command);

    /**
     * Changes a tier, including publishing and retiring it (payments:configure; a reason; the version the editor
     * loaded). A published tier never goes back to draft.
     */
    MembershipTier update(SaveTier command);

    /** The audit history of a tier, newest first (staff with payments:read). */
    List<MembershipTierRepository.Change> changes(String code, int limit);

    /**
     * @param expectedVersion the version the editor loaded; null when creating
     * @param reason          why (at least 15 characters)
     */
    record SaveTier(String code, String name, String description, TierStatus status, int sortOrder,
                    long monthlyPriceMinor, long yearlyPriceMinor, int trialDays, List<TierBenefit> benefits,
                    String reason, Long expectedVersion) {
    }

    /** A tier with its members (trialling members counted apart). */
    record TierOverview(MembershipTier tier, long members, long trialing) {
    }
}
