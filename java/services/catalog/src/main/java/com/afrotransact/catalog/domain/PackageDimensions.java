package com.afrotransact.catalog.domain;

/** Shipping package size (inches) and weight (pounds). Required for Fulfilled by AfroTransact. */
public record PackageDimensions(double lengthIn, double widthIn, double heightIn, double weightLb) {

    public static final double MAX_EDGE_IN = 108;
    public static final double MAX_WEIGHT_LB = 150;

    /** Why the package is not acceptable, or null. */
    public String problem() {
        if (lengthIn <= 0 || widthIn <= 0 || heightIn <= 0) {
            return "Length, width and height must be above zero";
        }
        if (lengthIn > MAX_EDGE_IN || widthIn > MAX_EDGE_IN || heightIn > MAX_EDGE_IN) {
            return "Each side must be at most " + (int) MAX_EDGE_IN + " in";
        }
        if (weightLb <= 0 || weightLb > MAX_WEIGHT_LB) {
            return "Weight must be above zero and at most " + (int) MAX_WEIGHT_LB + " lb";
        }
        return null;
    }
}
