package com.afrotransact.trustops.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.trustops.domain.SellerHealthReport;
import com.afrotransact.trustops.port.ShipmentFact;

/**
 * S120 / S223 seller health: late shipment, cancellation and order defect rates from orders events and claims;
 * computed daily per seller and pushed to offers' buy box ({@code PUT /v1/sellers/{orgId}/performance}).
 */
public interface SellerHealthService {

    /** Projects the shipments an orders event describes (inbox-deduplicated by event id). */
    void recordShipments(UUID eventId, List<ShipmentFact> facts);

    /** Live metrics for one seller (orgs:read). */
    SellerHealthView health(String orgId);

    /** Computes, stores and pushes one seller now (sellers:enforce). */
    SellerHealthReport recompute(String orgId);

    /** Daily job: every seller active in the window plus every suspended seller. Returns sellers processed. */
    int computeAll();
}
