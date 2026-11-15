package com.afrotransact.offers.api;

import java.util.Optional;

/**
 * What the trust-ops module asks offers in-process about a seller's standing: the rating it keeps and the performance inputs
 * it pushes ({@code GET /v1/sellers/{orgId}/standing}, {@code PUT /v1/sellers/{orgId}/performance}; the push needs
 * {@code offers:write}). Entered under the caller's in-process principal; a caller without the scope is refused with
 * {@code com.afrotransact.module.ModuleApiException} status 403.
 */
public interface SellerStandingApi {

    /** @return the seller's current rating, empty when offers has none (HTTP 404 or a null rating) */
    Optional<Double> rating(String orgId);

    /** Sets the performance inputs; recomputes the buy box of every ATIN the seller offers. */
    void updatePerformance(String orgId, Double rating, Double onTimeRate, Double defectRate, boolean restricted);
}
