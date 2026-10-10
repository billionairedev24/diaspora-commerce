package com.afrotransact.offers.application;

/** Seller performance inputs pushed by staff tooling or the seller-health job. */
public record SellerPerformanceCommand(Double rating, Double onTimeRate, Double defectRate, boolean restricted) {
}
