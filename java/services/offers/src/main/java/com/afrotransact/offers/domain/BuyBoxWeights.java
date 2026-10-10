package com.afrotransact.offers.domain;

/**
 * Tunable inputs of {@link DefaultBuyBoxPolicy}. Points add up to 1000 by default.
 *
 * @param pricePoints       points for the lowest landed price; others get pricePoints × lowest / theirs
 * @param fulfilmentPoints  bonus for Fulfilled by AfroTransact
 * @param deliveryPoints    points for the fastest delivery estimate; others get deliveryPoints × fastest / theirs
 * @param performancePoints points × seller health (0–1)
 * @param stockPoints       points × min(1, available / fullStockUnits)
 * @param fullStockUnits    units at which stock depth earns full points
 * @param maxDefectRate     order defect rate above which a seller cannot win
 * @param minOnTimeRate     on-time rate below which a seller cannot win
 * @param newSellerRating   rating assumed for sellers without ratings yet
 */
public record BuyBoxWeights(double pricePoints, double fulfilmentPoints, double deliveryPoints,
                            double performancePoints, double stockPoints, int fullStockUnits, double maxDefectRate,
                            double minOnTimeRate, double newSellerRating) {

    public BuyBoxWeights {
        if (pricePoints < 0 || fulfilmentPoints < 0 || deliveryPoints < 0 || performancePoints < 0
                || stockPoints < 0) {
            throw new IllegalArgumentException("Buy box points cannot be negative");
        }
        if (fullStockUnits < 1) {
            throw new IllegalArgumentException("fullStockUnits must be at least 1");
        }
        if (maxDefectRate <= 0 || maxDefectRate > 1 || minOnTimeRate < 0 || minOnTimeRate > 1) {
            throw new IllegalArgumentException("Rates must be between 0 and 1");
        }
        if (newSellerRating < 0 || newSellerRating > 5) {
            throw new IllegalArgumentException("newSellerRating must be between 0 and 5");
        }
    }

    public static BuyBoxWeights defaults() {
        return new BuyBoxWeights(600, 100, 100, 150, 50, 10, 0.02, 0.80, 4.0);
    }
}
