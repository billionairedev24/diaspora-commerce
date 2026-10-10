package com.afrotransact.offers.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Result of a buy box run for one ATIN and currency.
 *
 * @param evaluations every candidate: eligible offers by rank, then listed-only offers, then unlisted offers
 * @param reasons     why the winner won (empty when there is no winner)
 */
public record BuyBoxDecision(UUID winnerOfferId, List<OfferEvaluation> evaluations, List<BuyBoxReason> reasons) {

    public BuyBoxDecision {
        evaluations = List.copyOf(evaluations);
        reasons = List.copyOf(reasons);
    }

    public Optional<OfferEvaluation> winner() {
        return evaluations.stream().filter(e -> e.rank() == 1).findFirst();
    }

    /**
     * Offers shown on the product page (winner included), sorted by landed price; equal landed prices keep the
     * buy box order (the sort is stable).
     */
    public List<OfferEvaluation> listed() {
        return evaluations.stream().filter(OfferEvaluation::listable)
                .sorted(Comparator.comparingLong(e -> e.candidate().landedPriceMinor()))
                .toList();
    }
}
