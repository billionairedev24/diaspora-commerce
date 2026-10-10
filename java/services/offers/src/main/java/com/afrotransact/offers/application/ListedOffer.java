package com.afrotransact.offers.application;

import java.util.List;

import com.afrotransact.offers.domain.Ineligibility;

/**
 * An offer as listed on the product page.
 *
 * @param buyBoxRank   1 = winner, 2.. = runner-up order, 0 = listed but not eligible for the buy box
 * @param notEligible  why a listed offer cannot win (used condition, seller health)
 */
public record ListedOffer(OfferView view, long landedPriceMinor, int buyBoxRank, double score,
                          List<OfferBadge> badges, List<Ineligibility> notEligible) {

    public ListedOffer {
        badges = List.copyOf(badges);
        notEligible = List.copyOf(notEligible);
    }
}
