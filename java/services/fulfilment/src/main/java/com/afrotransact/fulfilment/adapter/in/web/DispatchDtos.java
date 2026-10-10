package com.afrotransact.fulfilment.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.fulfilment.domain.ContactAttempt;
import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.DeliveryShipment;
import com.afrotransact.fulfilment.domain.RoutePackage;
import com.afrotransact.fulfilment.domain.RouteStop;
import com.afrotransact.fulfilment.domain.SosAlert;
import com.afrotransact.fulfilment.port.DeliveryShipmentRepository;
import com.afrotransact.fulfilment.port.DriverEventLog;
import com.afrotransact.fulfilment.port.InspectionRepository;
import com.afrotransact.fulfilment.port.OrdersCommandQueue;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Dispatch console and route views (also the driver's offline manifest). */
final class DispatchDtos {

    private DispatchDtos() {
    }

    @Schema(name = "CreateRouteRequest")
    record CreateRouteRequest(@Size(max = 120) String name, @Size(max = 120) String zone,
                              @NotNull LocalDate serviceDate,
                              @Schema(example = "1:00–5:00 pm") @Size(max = 60) String window,
                              @NotBlank String depotNodeId, List<String> shipmentIds) {
    }

    @Schema(name = "BuildRoutesRequest", description = "Route builder: group the depot's waiting shipments by "
            + "delivery zone (geo-service zones, ZIP fallback)")
    record BuildRoutesRequest(@NotNull LocalDate serviceDate, @NotBlank String depotNodeId,
                              @Min(1) @Max(200) Integer maxDropoffs, @Size(max = 60) String window) {
    }

    @Schema(name = "AddShipmentsRequest")
    record AddShipmentsRequest(@NotEmpty List<String> shipmentIds) {
    }

    @Schema(name = "AssignRequest")
    record AssignRequest(@NotBlank String driverId, @Size(max = 60) String vehicleId) {
    }

    @Schema(name = "ReasonRequest")
    record ReasonRequest(@Size(max = 500) String reason) {
    }

    @Schema(name = "ReassignRequest", description = "Move the stop to another open route")
    record ReassignRequest(@NotBlank String targetRouteId) {
    }

    @Schema(name = "ResolveDefectRequest")
    record ResolveDefectRequest(@NotBlank String routeId, @Size(max = 500) String note) {
    }

    @Schema(name = "Route", description = "A delivery route with stops and packages (the driver's offline manifest)")
    record RouteView(String id, String name, String zone, LocalDate serviceDate, String window, String depotNodeId,
                     String driverId, String vehicleId,
                     @Schema(description = "PLANNED | ASSIGNED | IN_PROGRESS | COMPLETED | CANCELLED") String status,
                     @Schema(description = "PASSED | PASSED_WITH_DEFECTS | FAILED_CRITICAL") String inspection,
                     boolean blocked, String blockedReason,
                     @Schema(description = "Perishables on board: cold box 33–40 °F, no safe place") boolean perishable,
                     Integer startOdometer, Integer endOdometer, Instant loadOutCompletedAt, Instant startedAt,
                     Instant endedAt, int version, List<StopView> stops, List<PackageView> packages) {

        static RouteView of(DeliveryRoute r) {
            return new RouteView(r.id(), r.name(), r.zone(), r.serviceDate(), r.window(), r.depotNodeId(),
                    r.driverId(), r.vehicleId(), r.status().name(),
                    r.inspection() == null ? null : r.inspection().name(), r.blocked(), r.blockedReason(),
                    r.perishable(), r.startOdometer(), r.endOdometer(), r.loadOutCompletedAt(), r.startedAt(),
                    r.endedAt(), r.version(), r.stops().stream().map(StopView::of).toList(),
                    r.packages().stream().map(PackageView::of).toList());
        }
    }

    @Schema(name = "RouteStop")
    record StopView(String id, int sequence, @Schema(description = "PICKUP | DROPOFF") String kind, String name,
                    String sellerOrgId, String orderNumber, String addressLine, String city, String postalCode,
                    Double lat, Double lon, String window, boolean handoverRequired, boolean safePlaceAllowed,
                    @Schema(description = "PENDING | ARRIVED | DONE | FAILED | RETRY | SKIPPED | REASSIGNED | "
                            + "CANCELLED") String status,
                    int reattempts, List<ContactAttempt> contacts, boolean partialAccepted, String handedOverBy,
                    String note, String handoverMethod, Double podDistanceMeters, String reassignedTo,
                    Instant updatedAt,
                    @Schema(description = "Seller ship-from location of a PICKUP stop (seller-service id; S259)")
                    String pickupLocationId) {

        static StopView of(RouteStop s) {
            return new StopView(s.id(), s.sequence(), s.kind().name(), s.name(), s.sellerOrgId(), s.orderNumber(),
                    s.addressLine(), s.city(), s.postalCode(), s.point() == null ? null : s.point().lat(),
                    s.point() == null ? null : s.point().lon(), s.window(), s.handoverRequired(),
                    s.safePlaceAllowed(), s.status().name(), s.reattempts(), s.contacts(), s.partialAccepted(),
                    s.handedOverBy(), s.note(), s.handoverMethod() == null ? null : s.handoverMethod().name(),
                    s.podDistanceMeters(), s.reassignedTo(), s.updatedAt(), s.pickupLocationId());
        }
    }

    @Schema(name = "RoutePackage")
    record PackageView(String id, String label, String shipmentId, String orderNumber,
                       @Schema(description = "FC (load-out) | SELLER (pickup stop)") String origin,
                       String pickupStopId, String dropoffStopId, boolean perishable,
                       @Schema(description = "EXPECTED | LOADED | PICKED_UP | NOT_PICKED_UP | DELIVERED | FAILED | "
                               + "TO_PICKUP_POINT | MISSING | RETURNED | REASSIGNED | CANCELLED") String status,
                       boolean pickupScanned, boolean dropScanned,
                       @Schema(description = "A scan was typed in by hand (flagged)") boolean manualEntry,
                       Instant updatedAt) {

        static PackageView of(RoutePackage p) {
            return new PackageView(p.id(), p.label(), p.shipmentId(), p.orderNumber(), p.origin().name(),
                    p.pickupStopId(), p.dropoffStopId(), p.perishable(), p.status().name(), p.pickupScanned(),
                    p.dropScanned(), p.manualEntry(), p.updatedAt());
        }
    }

    @Schema(name = "DeliveryShipment", description = "AfroTransact Delivery shipment in the dispatch pool")
    record ShipmentView(String shipmentId, String orderId, String orderNumber, String sellerOrgId, String sellerName,
                        String fulfilmentType, String origin, String nodeId, String label, String city, String state,
                        String postalCode, boolean perishable, boolean handoverRequired, int units, String status,
                        String routeId, LocalDate promisedBy, Instant createdAt, LocalDate shipBy,
                        @Schema(description = "Seller ship-from location where the driver collects (SELLER origin)")
                        String pickupLocationId, Double pickupLat, Double pickupLon) {

        static ShipmentView of(DeliveryShipment s) {
            return new ShipmentView(s.shipmentId(), s.orderId(), s.orderNumber(), s.sellerOrgId(), s.sellerName(),
                    s.fulfilmentType(), s.origin().name(), s.nodeId(), s.label(), s.city(), s.state(),
                    s.postalCode(), s.perishable(), s.handoverRequired(), s.units(), s.status().name(), s.routeId(),
                    s.promisedBy(), s.createdAt(), s.shipBy(), s.pickupLocationId(),
                    s.pickupPoint() == null ? null : s.pickupPoint().lat(),
                    s.pickupPoint() == null ? null : s.pickupPoint().lon());
        }
    }

    @Schema(name = "SellerPickup", description = "A seller pickup (SELLER-origin AfroTransact Delivery shipment), "
            + "routed or waiting for a route, with its pickup location, route and pickup stop")
    record PickupView(ShipmentView shipment, boolean routed, String routeId, String routeName,
                      String driverId, LocalDate serviceDate, String routeStatus, String pickupStopId,
                      String pickupStopStatus,
                      @Schema(description = "Package status on the route (EXPECTED, PICKED_UP …); null while unrouted")
                      String packageStatus) {

        static PickupView of(DeliveryShipmentRepository.PickupListing l) {
            DeliveryShipment s = l.shipment();
            return new PickupView(ShipmentView.of(s), s.routeId() != null, s.routeId(), l.routeName(), l.driverId(),
                    l.serviceDate(), l.routeStatus(), l.pickupStopId(), l.pickupStopStatus(), l.packageStatus());
        }
    }

    @Schema(name = "SosAlert")
    record SosView(String alertId, String driverId, String routeId, String kind, Double lat, Double lon, String note,
                   Instant raisedAt, Instant receivedAt) {

        static SosView of(SosAlert a) {
            return new SosView(a.eventId(), a.driverId(), a.routeId(), a.kind().name(),
                    a.location() == null ? null : a.location().lat(), a.location() == null ? null
                    : a.location().lon(), a.note(), a.raisedAt(), a.receivedAt());
        }
    }

    @Schema(name = "VehicleDefect")
    record DefectView(String id, String routeId, String vehicleId, String driverId, boolean critical,
                      List<String> items, String note, Instant reportedAt) {

        static DefectView of(InspectionRepository.Defect d) {
            return new DefectView(d.id(), d.routeId(), d.vehicleId(), d.driverId(), d.critical(), d.items(), d.note(),
                    d.reportedAt());
        }
    }

    @Schema(name = "DriverEventLogEntry", description = "A driver event as received, with its outcome")
    record EventLogView(String eventId, String deviceId, long sequence, String driverId, String type, String routeId,
                        String stopId, Instant capturedAt, Instant receivedAt, String outcome, String code,
                        String message) {

        static EventLogView of(DriverEventLog.Entry e) {
            return new EventLogView(e.eventId(), e.deviceId(), e.sequence(), e.driverId(), e.type(), e.routeId(),
                    e.stopId(), e.capturedAt(), e.receivedAt(), e.outcome(), e.code(), e.message());
        }
    }

    @Schema(name = "ForwardFailure", description = "A call to orders-service that could not be applied")
    record ForwardFailureView(long id, String shipmentId, String kind, String target, int attempts, String lastError) {

        static ForwardFailureView of(OrdersCommandQueue.Command c) {
            return new ForwardFailureView(c.id(), c.shipmentId(), c.kind(), c.target(), c.attempts(), c.lastError());
        }
    }

    @Schema(name = "DriverAccess")
    record DriverAccessView(String driverId, String status, Instant changedAt, String reason, String source) {
    }
}
