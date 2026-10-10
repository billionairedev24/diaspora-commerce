package com.afrotransact.offers.domain;

import java.util.List;

/**
 * Picks the buy box winner among the offers of one ATIN in one currency. Implementations must be deterministic
 * (same inputs, same winner and order) and explainable (every score has components and reasons).
 */
public interface BuyBoxPolicy {

    BuyBoxDecision decide(List<BuyBoxCandidate> candidates);
}
