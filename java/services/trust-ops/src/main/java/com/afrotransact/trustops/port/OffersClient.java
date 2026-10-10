package com.afrotransact.trustops.port;

/**
 * offers-service {@code PUT /v1/sellers/{orgId}/performance} (offers:write, staff or seller-health job): seller
 * performance inputs for the buy box. Null rates mean unknown (neutral). The seller's current rating is kept.
 */
public interface OffersClient {

    void pushPerformance(String orgId, Double onTimeRate, Double defectRate, boolean restricted);
}
