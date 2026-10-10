package com.afrotransact.fulfilment.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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

    /** fulfilment.orders.forward_failed (key = shipment id). */
    public record ForwardFailed(String shipmentId, String kind, String target, String code, String message,
                                Instant at) {
    }
}
