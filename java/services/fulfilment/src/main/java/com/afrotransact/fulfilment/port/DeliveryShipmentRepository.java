package com.afrotransact.fulfilment.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.DeliveryShipment;
import com.afrotransact.fulfilment.domain.DeliveryShipmentStatus;

/** AfroTransact Delivery shipments known to dispatch. */
public interface DeliveryShipmentRepository {

    /** @return true when inserted, false when the shipment is already known */
    boolean insert(DeliveryShipment shipment);

    Optional<DeliveryShipment> find(String shipmentId);

    /** Locks the given shipments (for routing). */
    List<DeliveryShipment> lockAll(List<String> shipmentIds);

    List<DeliveryShipment> awaitingRoute(String nodeId, int limit);

    void updateStatus(String shipmentId, DeliveryShipmentStatus status, String routeId);

    void updateLabel(String shipmentId, String label);

    /**
     * Seller pickups (SELLER-origin shipments) still waiting for a route or on one, with their route and pickup
     * stop (S259), waiting ones first, oldest first.
     */
    List<PickupListing> pickups(PickupQuery query);

    /**
     * Filter of {@link #pickups}.
     *
     * @param routed           true = only routed, false = only waiting for a route, null = both
     * @param sellerOrgId      one seller, or null
     * @param pickupLocationId one ship-from location, or null
     * @param serviceDate      routed pickups on routes of this day (unrouted ones are always included), or null
     */
    record PickupQuery(Boolean routed, String sellerOrgId, String pickupLocationId, LocalDate serviceDate,
                       int limit) {
    }

    /**
     * A seller pickup with what dispatch needs to see whether and how it is routed.
     *
     * @param routeName        route name (null while unrouted)
     * @param driverId         route driver (null while unrouted or unassigned)
     * @param serviceDate      route day
     * @param routeStatus      route status
     * @param pickupStopId     the route's PICKUP stop for this package
     * @param pickupStopStatus that stop's status
     * @param packageStatus    the package on the route (EXPECTED, PICKED_UP …)
     */
    record PickupListing(DeliveryShipment shipment, String routeName, String driverId, LocalDate serviceDate,
                         String routeStatus, String pickupStopId, String pickupStopStatus, String packageStatus) {
    }
}
