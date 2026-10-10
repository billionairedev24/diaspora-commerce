package com.afrotransact.offers.domain;

import java.util.List;

/**
 * The policy's verdict on one offer.
 *
 * @param rank  1-based position among buy-box-eligible offers (1 = winner), 0 when not eligible
 * @param score total points (0 when not eligible)
 */
public record OfferEvaluation(BuyBoxCandidate candidate, boolean listable, boolean buyBoxEligible,
                              List<Ineligibility> ineligibility, double score, List<ScoreComponent> components,
                              int rank) {

    public OfferEvaluation {
        ineligibility = List.copyOf(ineligibility);
        components = List.copyOf(components);
    }
}
