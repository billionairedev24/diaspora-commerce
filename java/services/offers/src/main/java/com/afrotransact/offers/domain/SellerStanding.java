package com.afrotransact.offers.domain;

import java.time.Instant;

/**
 * What the buy box knows about a seller organisation: display name, performance inputs and enforcement flags.
 *
 * @param rating         average customer rating 0–5, null for a new seller
 * @param onTimeRate     share of orders shipped / delivered on time 0–1, null when unknown
 * @param defectRate     order defect rate 0–1 (A-to-z claims, chargebacks, negative feedback), null when unknown
 * @param restricted     set by Trust &amp; Safety: the seller's offers are not listed at all
 * @param listingsHidden set by seller-service enforcement (seller.listings.hidden): offers are suppressed
 */
public record SellerStanding(String orgId, String displayName, Double rating, Double onTimeRate, Double defectRate,
                             boolean restricted, boolean listingsHidden, Instant updatedAt) {

    public static SellerStanding unknown(String orgId) {
        return new SellerStanding(orgId, null, null, null, null, false, false, null);
    }

    public SellerStanding withListingsHidden(boolean hidden, Instant now) {
        return new SellerStanding(orgId, displayName, rating, onTimeRate, defectRate, restricted, hidden, now);
    }

    public SellerStanding withDisplayName(String name, Instant now) {
        return new SellerStanding(orgId, name, rating, onTimeRate, defectRate, restricted, listingsHidden, now);
    }

    public SellerStanding withPerformance(Double newRating, Double newOnTime, Double newDefect, boolean newRestricted,
                                          Instant now) {
        if (newRating != null && (newRating < 0 || newRating > 5)) {
            throw new OfferRuleViolation("Rating must be between 0 and 5");
        }
        if (newOnTime != null && (newOnTime < 0 || newOnTime > 1)) {
            throw new OfferRuleViolation("On-time rate must be between 0 and 1");
        }
        if (newDefect != null && (newDefect < 0 || newDefect > 1)) {
            throw new OfferRuleViolation("Defect rate must be between 0 and 1");
        }
        return new SellerStanding(orgId, displayName, newRating, newOnTime, newDefect, newRestricted, listingsHidden,
                now);
    }
}
