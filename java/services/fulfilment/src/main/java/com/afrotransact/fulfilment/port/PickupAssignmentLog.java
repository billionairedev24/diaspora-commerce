package com.afrotransact.fulfilment.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Who got a seller pickup and why: automatic nearest-driver assignments and dispatch overrides. */
public interface PickupAssignmentLog {

    void record(Entry entry);

    List<Entry> forShipment(String shipmentId);

    /**
     * One assignment.
     *
     * @param mode   AUTO_NEAREST or DISPATCH_OVERRIDE
     * @param reason why dispatch overrode it (required for overrides)
     */
    record Entry(UUID id, String shipmentId, String routeId, String driverId, String mode, String reason,
                 String previousRouteId, Double straightMiles, Double roadMiles, String distanceMethod, String actor,
                 Instant createdAt) {
    }
}
