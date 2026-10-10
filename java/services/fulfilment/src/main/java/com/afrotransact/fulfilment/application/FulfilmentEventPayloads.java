package com.afrotransact.fulfilment.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.fulfilment.domain.ReturnPickup;

/** Payloads of the events published on {@code at.fulfilment.events}. */
public final class FulfilmentEventPayloads {

    private FulfilmentEventPayloads() {
    }

    /** fulfilment.task.* (key = shipment id). */
    public record TaskEvent(String taskId, String shipmentId, String orderId, String orderNumber, String nodeId,
                            String status, String packageLabel, String reason, Instant at) {
    }

    /** fulfilment.route.* (key = route id). */
    public record RouteEvent(String routeId, String zone, LocalDate serviceDate, String depotNodeId, String driverId,
                             String vehicleId, String status, int stops, int packages, Instant at) {
    }

    /**
     * fulfilment.pickup.auto_assigned / dispatch_assigned (S255, key = shipment id).
     *
     * @param mode AUTO_NEAREST or DISPATCH_OVERRIDE
     */
    public record PickupAssigned(String shipmentId, String orderId, String orderNumber, String sellerOrgId,
                                 String pickupLocationId, String routeId, String driverId, String mode,
                                 String reason, String previousRouteId, Double straightLineMiles, Double roadMiles,
                                 Double durationMinutes, String distanceMethod, Instant at) {
    }

    /** fulfilment.stop.* (key = route id). */
    public record StopEvent(String routeId, String stopId, String driverId, String kind, String status,
                            List<String> shipmentIds, String reason, String handoverMethod, String targetRouteId,
                            Instant at) {
    }

    /** fulfilment.vehicle.defect_reported (key = route id). */
    public record DefectEvent(String defectId, String routeId, String vehicleId, String driverId, boolean critical,
                              boolean routeBlocked, List<String> items, String note, String priority, Instant at) {
    }

    /** fulfilment.driver.sos_raised (key = route id or driver id). */
    public record SosEvent(String alertId, String driverId, String routeId, String kind, Double lat, Double lon,
                           String note, String priority, Instant raisedAt) {
    }

    /** fulfilment.driver.access_revoked (key = driver id). */
    public record AccessRevoked(String driverId, String reason, Instant revokedAt) {
    }

    /** fulfilment.return_pickup.* (key = RMA id). */
    public record ReturnPickupEvent(String returnPickupId, String rmaId, String rmaNumber, String orderId,
                                    String shipmentId, String customerId, String sellerOrgId, String depotNodeId,
                                    String status, String routeId, String stopId, LocalDate collectBy, String note,
                                    Instant at) {

        static ReturnPickupEvent of(ReturnPickup p, Instant at) {
            return new ReturnPickupEvent(p.id(), p.rmaId(), p.rmaNumber(), p.orderId(), p.shipmentId(),
                    p.customerId(), p.sellerOrgId(), p.depotNodeId(), p.status().name(), p.routeId(), p.stopId(),
                    p.collectBy(), p.note(), at);
        }
    }

    /**
     * fulfilment.shipment.handed_over (key = shipment id): the shipment left its origin — collected at the seller
     * (origin SELLER) or loaded out at the FC (origin FC). {@code late} = handed over after the ship-by date
     * (business zone); trust-ops uses SELLER-origin events for seller-health lateness.
     */
    public record HandedOver(String shipmentId, String orderId, String orderNumber, String sellerOrgId,
                             String fulfilmentType, String origin, String routeId, String driverId,
                             LocalDate shipBy, Instant handedOverAt, Boolean late) {
    }

    /** fulfilment.orders.forward_failed (key = shipment id). */
    public record ForwardFailed(String shipmentId, String kind, String target, String code, String message,
                                Instant at) {
    }
}
