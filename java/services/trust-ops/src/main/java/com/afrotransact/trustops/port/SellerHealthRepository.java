package com.afrotransact.trustops.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.trustops.domain.SellerHealthMetrics;
import com.afrotransact.trustops.domain.SellerHealthReport;

/** Shipment projection from orders events, metric queries and stored health snapshots. */
public interface SellerHealthRepository {

    void apply(ShipmentFact fact, Instant now);

    /** Metrics over shipments placed (and claims opened) in [from, to). */
    SellerHealthMetrics metrics(String orgId, Instant from, Instant to);

    /** Sellers with at least one shipment placed since {@code from}. */
    List<String> sellersActiveSince(Instant from);

    void saveSnapshot(SellerHealthReport report, boolean pushedToOffers);

    Optional<StoredHealth> latest(String orgId);

    /** A stored snapshot and whether it reached offers-service. */
    record StoredHealth(SellerHealthReport report, boolean pushedToOffers) {
    }
}
