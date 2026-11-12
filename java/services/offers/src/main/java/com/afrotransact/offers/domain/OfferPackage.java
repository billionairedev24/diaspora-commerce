package com.afrotransact.offers.domain;

/**
 * The box a seller ships an item in, stored on the offer (S383). Only Fulfilled by AfroTransact offers carry one: the
 * physical box depends on the seller, not on the page. Inches and pounds, like the catalog package. The largest edge and
 * weight are settings, checked by the offer service.
 */
public record OfferPackage(double lengthIn, double widthIn, double heightIn, double weightLb) {

    public OfferPackage {
        if (lengthIn <= 0 || widthIn <= 0 || heightIn <= 0 || weightLb <= 0) {
            throw new IllegalArgumentException("Length, width, height and weight must be above zero");
        }
    }

    /** The longest edge. */
    public double longestEdgeIn() {
        return Math.max(lengthIn, Math.max(widthIn, heightIn));
    }
}
