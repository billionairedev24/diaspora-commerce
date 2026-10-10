package com.afrotransact.offers.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.offers.domain.BuyBoxReason;
import com.afrotransact.offers.domain.OfferEvaluation;

/**
 * Full scoring of a buy box for seller center and the staff console. Sellers see the winner and their own offers;
 * staff see every offer.
 */
public record BuyBoxExplanation(String atin, String currency, UUID winnerOfferId, List<BuyBoxReason> reasons,
                                List<OfferEvaluation> evaluations) {

    public BuyBoxExplanation {
        reasons = List.copyOf(reasons);
        evaluations = List.copyOf(evaluations);
    }
}
