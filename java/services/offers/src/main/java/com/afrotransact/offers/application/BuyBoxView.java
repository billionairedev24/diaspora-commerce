package com.afrotransact.offers.application;

import java.util.List;

import com.afrotransact.offers.domain.BuyBoxReason;

/**
 * The buy box of one ATIN in one currency, plus the "other sellers" list.
 *
 * @param winner              null when no offer is eligible ("Currently unavailable")
 * @param otherOffers         every other listed offer, sorted by landed price
 * @param offerCount          listed offers including the winner
 * @param minLandedPriceMinor lowest landed price among listed offers, null when none
 */
public record BuyBoxView(String atin, String currency, ListedOffer winner, List<BuyBoxReason> reasons,
                         List<ListedOffer> otherOffers, int offerCount, Long minLandedPriceMinor,
                         Long minPriceMinor) {

    public BuyBoxView {
        reasons = List.copyOf(reasons);
        otherOffers = List.copyOf(otherOffers);
    }
}
