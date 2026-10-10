package com.afrotransact.fulfilment.adapter.in.web;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.AddShipmentsRequest;
import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.AssignRequest;
import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.BuildRoutesRequest;
import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.CreateRouteRequest;
import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.DefectView;
import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.DriverAccessView;
import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.EventLogView;
import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.ForwardFailureView;
import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.PickupView;
import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.ReasonRequest;
import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.ReassignRequest;
import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.ResolveDefectRequest;
import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.RouteView;
import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.ShipmentView;
import com.afrotransact.fulfilment.adapter.in.web.DispatchDtos.SosView;
import com.afrotransact.fulfilment.application.BuildRoutesCommand;
import com.afrotransact.fulfilment.application.CreateRouteCommand;
import com.afrotransact.fulfilment.application.DispatchService;
import com.afrotransact.fulfilment.domain.DriverAccess;
import com.afrotransact.fulfilment.domain.RouteStatus;
import com.afrotransact.fulfilment.port.DeliveryShipmentRepository.PickupQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Dispatch", description = "Delivery dispatch console (delivery:dispatch): routes per zone, drivers, "
        + "reassignment of failed stops, SOS and defects, live driver events")
@RestController
class DispatchController {

    private final DispatchService dispatch;
    private final SseHub hub;

    DispatchController(DispatchService dispatch, SseHub hub) {
        this.dispatch = dispatch;
        this.hub = hub;
    }

    @Operation(operationId = "createRoute", summary = "Create a route (optionally with shipments from the pool)")
    @PostMapping("/v1/dispatch/routes")
    @ResponseStatus(HttpStatus.CREATED)
    RouteView create(@Valid @RequestBody CreateRouteRequest b) {
        return RouteView.of(dispatch.create(new CreateRouteCommand(b.name(), b.zone(), b.serviceDate(), b.window(),
                b.depotNodeId(), b.shipmentIds())));
    }

    @Operation(operationId = "buildRoutes",
            summary = "Route builder: plan the depot's waiting shipments into routes per delivery zone",
            description = "Zones come from geo-service (ZIP centroid → active DELIVERY zone covering it); without a "
                    + "zone the ZIP is the group. One drop-off per order, seller pickups first, at most maxDropoffs "
                    + "drop-offs per route (default 25).")
    @PostMapping("/v1/dispatch/routes/build")
    @ResponseStatus(HttpStatus.CREATED)
    List<RouteView> build(@Valid @RequestBody BuildRoutesRequest b) {
        return dispatch.build(new BuildRoutesCommand(b.serviceDate(), b.depotNodeId(),
                b.maxDropoffs() == null ? 25 : b.maxDropoffs(), b.window())).stream().map(RouteView::of).toList();
    }

    @Operation(operationId = "listRoutes", summary = "List routes (fulfilment:operate or delivery:dispatch)")
    @GetMapping("/v1/dispatch/routes")
    List<RouteView> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                         LocalDate date,
                         @RequestParam(required = false) String status,
                         @RequestParam(required = false) String driverId,
                         @RequestParam(defaultValue = "50") int limit) {
        return dispatch.list(date, Enums.strict(RouteStatus.class, status), driverId, limit).stream()
                .map(RouteView::of).toList();
    }

    @Operation(operationId = "getRoute", summary = "Route with stops and packages")
    @GetMapping("/v1/dispatch/routes/{routeId}")
    RouteView get(@PathVariable String routeId) {
        return RouteView.of(dispatch.get(routeId));
    }

    @Operation(operationId = "addShipmentsToRoute",
            summary = "Add pool shipments to a route (drop-offs; seller pickups added automatically)")
    @PostMapping("/v1/dispatch/routes/{routeId}/shipments")
    RouteView addShipments(@PathVariable String routeId, @Valid @RequestBody AddShipmentsRequest b) {
        return RouteView.of(dispatch.addShipments(routeId, b.shipmentIds()));
    }

    @Operation(operationId = "removeStop",
            summary = "Remove a stop before the route starts (its shipments go back to the pool)")
    @DeleteMapping("/v1/dispatch/routes/{routeId}/stops/{stopId}")
    RouteView removeStop(@PathVariable String routeId, @PathVariable String stopId) {
        return RouteView.of(dispatch.removeStop(routeId, stopId));
    }

    @Operation(operationId = "assignRoute",
            summary = "Assign a driver (and vehicle)", description = "A different vehicle needs a new inspection.")
    @PostMapping("/v1/dispatch/routes/{routeId}/assign")
    RouteView assign(@PathVariable String routeId, @Valid @RequestBody AssignRequest b) {
        return RouteView.of(dispatch.assign(routeId, b.driverId(), b.vehicleId()));
    }

    @Operation(operationId = "cancelRoute",
            summary = "Cancel a route that has not started (shipments go back to the pool)")
    @PostMapping("/v1/dispatch/routes/{routeId}/cancel")
    RouteView cancel(@PathVariable String routeId, @Valid @RequestBody ReasonRequest b) {
        return RouteView.of(dispatch.cancel(routeId, b.reason()));
    }

    @Operation(operationId = "reassignStop",
            summary = "Reassign a failed, retry, skipped or pending drop-off to another open route",
            description = "Packages still on the old van are marked for return to the FC; the new route loads them "
                    + "at the FC (or picks them up at the seller when they never left). Returns the target route.")
    @PostMapping("/v1/dispatch/routes/{routeId}/stops/{stopId}/reassign")
    RouteView reassign(@PathVariable String routeId, @PathVariable String stopId,
                       @Valid @RequestBody ReassignRequest b) {
        return RouteView.of(dispatch.reassignStop(routeId, stopId, b.targetRouteId()));
    }

    @Operation(operationId = "listAwaitingShipments", summary = "Shipments waiting for a route")
    @GetMapping("/v1/dispatch/shipments")
    List<ShipmentView> pool(@RequestParam(required = false) String nodeId,
                            @RequestParam(defaultValue = "200") int limit) {
        return dispatch.awaitingRoute(nodeId, limit).stream().map(ShipmentView::of).toList();
    }

    @Operation(operationId = "listSellerPickups", summary = "Seller pickups, routed and waiting for a route (S259)",
            description = "SELLER-origin AfroTransact Delivery shipments with their pickup location, and for routed ones "
                    + "the route, driver, pickup stop and package status. routed=true / false narrows the list; "
                    + "serviceDate limits routed pickups to that day's routes (unrouted ones are always listed).")
    @GetMapping("/v1/dispatch/pickups")
    List<PickupView> pickups(@RequestParam(required = false) Boolean routed,
                             @RequestParam(required = false) String sellerOrgId,
                             @RequestParam(required = false) String pickupLocationId,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                             LocalDate serviceDate,
                             @RequestParam(defaultValue = "200") int limit) {
        return dispatch.pickups(new PickupQuery(routed, sellerOrgId, pickupLocationId, serviceDate, limit)).stream()
                .map(PickupView::of).toList();
    }

    @Operation(operationId = "listSos", summary = "Open SOS alerts")
    @GetMapping("/v1/dispatch/sos")
    List<SosView> sos() {
        return dispatch.openSos().stream().map(SosView::of).toList();
    }

    @Operation(operationId = "acknowledgeSos", summary = "Acknowledge an SOS alert")
    @PostMapping("/v1/dispatch/sos/{alertId}/acknowledge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void acknowledge(@PathVariable String alertId) {
        dispatch.acknowledgeSos(alertId);
    }

    @Operation(operationId = "listDefects", summary = "Open vehicle defects")
    @GetMapping("/v1/dispatch/defects")
    List<DefectView> defects() {
        return dispatch.openDefects().stream().map(DefectView::of).toList();
    }

    @Operation(operationId = "resolveDefect",
            summary = "Resolve a defect and unblock the route (the driver re-inspects before starting)")
    @PostMapping("/v1/dispatch/defects/{defectId}/resolve")
    RouteView resolveDefect(@PathVariable String defectId, @Valid @RequestBody ResolveDefectRequest b) {
        return RouteView.of(dispatch.resolveDefect(defectId, b.routeId(), b.note()));
    }

    @Operation(operationId = "listDriverEvents", summary = "Driver events as received (newest first)")
    @GetMapping("/v1/dispatch/events")
    List<EventLogView> events(@RequestParam(required = false) String routeId,
                              @RequestParam(required = false) String driverId,
                              @RequestParam(defaultValue = "100") int limit) {
        return dispatch.driverEvents(routeId, driverId, limit).stream().map(EventLogView::of).toList();
    }

    @Operation(operationId = "listForwardFailures", summary = "Calls to orders-service that could not be applied")
    @GetMapping("/v1/dispatch/forward-failures")
    List<ForwardFailureView> forwardFailures(@RequestParam(defaultValue = "50") int limit) {
        return dispatch.forwardFailures(limit).stream().map(ForwardFailureView::of).toList();
    }

    @Operation(operationId = "suspendDriver",
            summary = "Suspend a driver now", description = "The app is told to sign out (driver stream "
            + "session.revoked, next sync signOut=true); events captured before the suspension still sync.")
    @PostMapping("/v1/dispatch/drivers/{driverId}/suspend")
    DriverAccessView suspend(@PathVariable String driverId, @Valid @RequestBody ReasonRequest b) {
        return view(dispatch.suspendDriver(driverId, b.reason()));
    }

    @Operation(operationId = "reinstateDriver", summary = "Lift a dispatch suspension")
    @PostMapping("/v1/dispatch/drivers/{driverId}/reinstate")
    DriverAccessView reinstate(@PathVariable String driverId) {
        return view(dispatch.reinstateDriver(driverId));
    }

    @Operation(operationId = "dispatchStream", summary = "Live stream of driver events (server-sent events)",
            description = "Event names: driver.event (every synced event with its outcome), driver.sos (HIGH), "
                    + "vehicle.defect, route.updated, stop.reassigned, shipment.cancelled, orders.forward_failed, "
                    + "session.revoked, driver.sos_acknowledged.")
    @GetMapping(path = "/v1/dispatch/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        dispatch.authorizeStream();
        return hub.dispatch();
    }

    private static DriverAccessView view(DriverAccess a) {
        return new DriverAccessView(a.driverId(), a.status().name(), a.changedAt(), a.reason(), a.source().name());
    }
}
