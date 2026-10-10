package com.afrotransact.offers.domain;

/** One factor's contribution to an offer's buy box score, with a human-readable explanation. */
public record ScoreComponent(BuyBoxFactor factor, double points, double maxPoints, String detail) {
}
