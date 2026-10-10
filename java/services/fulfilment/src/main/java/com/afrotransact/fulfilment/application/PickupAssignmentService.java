package com.afrotransact.fulfilment.application;

import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.DeliveryShipment;
import com.afrotransact.fulfilment.domain.DriverSuggestion;
import com.afrotransact.fulfilment.port.PickupAssignmentLog;

/**
 * Nearest-driver preference for seller pickups (S255, AfroTransact Delivery from the seller's store): the nearest
 * available on-shift driver (PostGIS KNN on fresh positions, re-ranked by geo road distance) whose assigned or
 * in-progress route today is in the pickup's delivery zone, passed its vehicle inspection and has room. The zone route
 * builder stays the fallback; dispatch can override with a reason.
 */
public interface PickupAssignmentService {

    /** Top 5 drivers for a SELLER-origin pickup with their distance (delivery:dispatch). */
    List<DriverSuggestion> suggestions(String shipmentId);

    /**
     * Joins a just-received SELLER-origin pickup to the nearest available driver's route; empty when none qualifies
     * (no pickup position, no zone, no driver) so the shipment waits for the zone route builder. Publishes
     * {@code fulfilment.pickup.auto_assigned}. Runs inside the intake transaction (no access check).
     */
    Optional<DeliveryRoute> autoAssign(DeliveryShipment shipment);

    /**
     * Dispatch override (delivery:dispatch): puts the pickup on {@code routeId} — from the pool, or from another
     * route while it was not collected yet — with a logged reason; publishes {@code fulfilment.pickup.dispatch_assigned}.
     */
    DeliveryRoute assign(String shipmentId, String routeId, String reason);

    /** Every assignment of a pickup, oldest first (delivery:dispatch). */
    List<PickupAssignmentLog.Entry> history(String shipmentId);
}
