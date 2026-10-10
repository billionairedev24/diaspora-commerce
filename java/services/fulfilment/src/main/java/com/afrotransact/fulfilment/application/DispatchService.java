package com.afrotransact.fulfilment.application;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.DeliveryShipment;
import com.afrotransact.fulfilment.domain.DriverAccess;
import com.afrotransact.fulfilment.domain.RouteStatus;
import com.afrotransact.fulfilment.domain.SosAlert;
import com.afrotransact.fulfilment.port.DeliveryShipmentRepository;
import com.afrotransact.fulfilment.port.DriverEventLog;
import com.afrotransact.fulfilment.port.InspectionRepository;
import com.afrotransact.fulfilment.port.OrdersCommandQueue;

/** Dispatch console (delivery:dispatch): plan routes per zone, assign drivers, reassign failed stops, react to SOS. */
public interface DispatchService {

    DeliveryRoute create(CreateRouteCommand command);

    /** Groups the depot's waiting shipments by delivery zone (geo-service zones, ZIP fallback) into routes. */
    List<DeliveryRoute> build(BuildRoutesCommand command);

    List<DeliveryRoute> list(LocalDate date, RouteStatus status, String driverId, int limit);

    DeliveryRoute get(String routeId);

    DeliveryRoute addShipments(String routeId, List<String> shipmentIds);

    DeliveryRoute removeStop(String routeId, String stopId);

    DeliveryRoute assign(String routeId, String driverId, String vehicleId);

    DeliveryRoute cancel(String routeId, String reason);

    /** Moves a failed, retry, skipped or pending drop-off to another open route. @return the target route */
    DeliveryRoute reassignStop(String routeId, String stopId, String targetRouteId);

    /** Clears a defect (vehicle repaired or replaced) so the route can be inspected again and started. */
    DeliveryRoute resolveDefect(String defectId, String routeId, String note);

    List<DeliveryShipment> awaitingRoute(String nodeId, int limit);

    /** Seller pickups, routed and unrouted, with their pickup location, route and stop (S259). */
    List<DeliveryShipmentRepository.PickupListing> pickups(DeliveryShipmentRepository.PickupQuery query);

    List<SosAlert> openSos();

    void acknowledgeSos(String alertId);

    List<InspectionRepository.Defect> openDefects();

    List<DriverEventLog.Entry> driverEvents(String routeId, String driverId, int limit);

    List<OrdersCommandQueue.Command> forwardFailures(int limit);

    /** Suspends a driver at once: the app is told to sign out; queued events captured before still sync. */
    DriverAccess suspendDriver(String driverId, String reason);

    DriverAccess reinstateDriver(String driverId);

    /** Checks delivery:dispatch before opening the live event stream. @return the dispatcher's id */
    String authorizeStream();
}
