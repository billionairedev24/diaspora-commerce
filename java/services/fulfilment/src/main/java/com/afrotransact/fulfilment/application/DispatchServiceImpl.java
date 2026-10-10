package com.afrotransact.fulfilment.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.DeliveryShipment;
import com.afrotransact.fulfilment.domain.DeliveryShipmentStatus;
import com.afrotransact.fulfilment.domain.DriverAccess;
import com.afrotransact.fulfilment.domain.FulfilmentConflict;
import com.afrotransact.fulfilment.domain.FulfilmentRuleViolation;
import com.afrotransact.fulfilment.domain.GeoPoint;
import com.afrotransact.fulfilment.domain.PackageOrigin;
import com.afrotransact.fulfilment.domain.PackageStatus;
import com.afrotransact.fulfilment.domain.PlannedRoute;
import com.afrotransact.fulfilment.domain.RoutableShipment;
import com.afrotransact.fulfilment.domain.RoutePackage;
import com.afrotransact.fulfilment.domain.RoutePlanner;
import com.afrotransact.fulfilment.domain.RouteStatus;
import com.afrotransact.fulfilment.domain.RouteStop;
import com.afrotransact.fulfilment.domain.SosAlert;
import com.afrotransact.fulfilment.port.CentreRepository;
import com.afrotransact.fulfilment.port.DeliveryShipmentRepository;
import com.afrotransact.fulfilment.port.DispatchFeed;
import com.afrotransact.fulfilment.port.DriverEventLog;
import com.afrotransact.fulfilment.port.GeoClient;
import com.afrotransact.fulfilment.port.IdIssuer;
import com.afrotransact.fulfilment.port.InspectionRepository;
import com.afrotransact.fulfilment.port.OrdersCommandQueue;
import com.afrotransact.fulfilment.port.ReturnPickupRepository;
import com.afrotransact.fulfilment.port.RouteRepository;
import com.afrotransact.fulfilment.port.SosRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DispatchServiceImpl implements DispatchService {

    private final ReturnPickupRepository returnPickups;
    private final FulfilmentAccess access;
    private final RouteRepository routes;
    private final DeliveryShipmentRepository shipments;
    private final CentreRepository centres;
    private final RouteAssembler assembler;
    private final RoutePlanner planner;
    private final GeoClient geo;
    private final IdIssuer ids;
    private final SosRepository sos;
    private final InspectionRepository inspections;
    private final DriverEventLog log;
    private final OrdersCommandQueue ordersQueue;
    private final DriverAccessService driverAccess;
    private final DomainEventPublisher events;
    private final DispatchFeed feed;
    private final Clock clock;

    DispatchServiceImpl(FulfilmentAccess access, RouteRepository routes, DeliveryShipmentRepository shipments,
                        CentreRepository centres, RouteAssembler assembler, RoutePlanner planner, GeoClient geo,
                        IdIssuer ids, SosRepository sos, InspectionRepository inspections, DriverEventLog log,
                        OrdersCommandQueue ordersQueue, DriverAccessService driverAccess,
                        DomainEventPublisher events, DispatchFeed feed, Clock clock,
                        ReturnPickupRepository returnPickups) {
        this.returnPickups = returnPickups;
        this.access = access;
        this.routes = routes;
        this.shipments = shipments;
        this.centres = centres;
        this.assembler = assembler;
        this.planner = planner;
        this.geo = geo;
        this.ids = ids;
        this.sos = sos;
        this.inspections = inspections;
        this.log = log;
        this.ordersQueue = ordersQueue;
        this.driverAccess = driverAccess;
        this.events = events;
        this.feed = feed;
        this.clock = clock;
    }

    @Override
    @Transactional
    public DeliveryRoute create(CreateRouteCommand c) {
        access.dispatcher();
        requireCentre(c.depotNodeId());
        if (c.serviceDate() == null) {
            throw new FulfilmentRuleViolation("fulfilment.service-date-required", "Choose the delivery day");
        }
        Instant now = Instant.now(clock);
        String id = ids.routeId();
        DeliveryRoute route = DeliveryRoute.plan(id, blank(c.name()) ? id : c.name().trim(),
                blank(c.zone()) ? "Manual" : c.zone().trim(), c.serviceDate(), c.window(), c.depotNodeId());
        routes.insert(route);
        if (!c.shipmentIds().isEmpty()) {
            route = routes.lock(id).orElseThrow();
            attach(route, c.shipmentIds(), now);
        }
        publishRoute(FulfilmentEvents.ROUTE_PLANNED, route, now);
        return route;
    }

    @Override
    @Transactional
    public List<DeliveryRoute> build(BuildRoutesCommand c) {
        access.dispatcher();
        requireCentre(c.depotNodeId());
        if (c.serviceDate() == null) {
            throw new FulfilmentRuleViolation("fulfilment.service-date-required", "Choose the delivery day");
        }
        Instant now = Instant.now(clock);
        List<DeliveryShipment> waiting = shipments.awaitingRoute(c.depotNodeId(), 2000);
        Map<String, DeliveryShipment> byId = new LinkedHashMap<>();
        Map<String, Optional<String>> zoneByZip = new HashMap<>();
        List<RoutableShipment> routable = new ArrayList<>();
        for (DeliveryShipment s : waiting) {
            byId.put(s.shipmentId(), s);
            routable.add(new RoutableShipment(s.shipmentId(), s.orderNumber(), s.origin(), s.sellerOrgId(),
                    s.postalCode(), zoneOf(s, zoneByZip)));
        }
        List<DeliveryRoute> built = new ArrayList<>();
        int n = 1;
        for (PlannedRoute plan : planner.plan(routable, c.maxDropoffs() <= 0 ? 25 : c.maxDropoffs())) {
            String id = ids.routeId();
            DeliveryRoute route = DeliveryRoute.plan(id, plan.zone() + " #" + n++, plan.zone(), c.serviceDate(),
                    c.window(), c.depotNodeId());
            routes.insert(route);
            route = routes.lock(id).orElseThrow();
            attach(route, plan.shipments().stream().map(RoutableShipment::shipmentId).toList(), now);
            publishRoute(FulfilmentEvents.ROUTE_PLANNED, route, now);
            built.add(route);
        }
        return built;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryRoute> list(LocalDate date, RouteStatus status, String driverId, int limit) {
        access.opsReader();
        return routes.list(date, status, driverId, Math.max(1, Math.min(limit, 200)));
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryRoute get(String routeId) {
        access.opsReader();
        return routes.find(routeId).orElseThrow(() -> routeNotFound(routeId));
    }

    @Override
    @Transactional
    public DeliveryRoute addShipments(String routeId, List<String> shipmentIds) {
        access.dispatcher();
        DeliveryRoute route = lock(routeId);
        attach(route, shipmentIds, Instant.now(clock));
        notifyDriver(route, "route.updated", Instant.now(clock));
        return route;
    }

    @Override
    @Transactional
    public DeliveryRoute removeStop(String routeId, String stopId) {
        access.dispatcher();
        DeliveryRoute route = lock(routeId);
        Instant now = Instant.now(clock);
        List<String> freed = route.packagesForDrop(stopId).stream().map(RoutePackage::shipmentId).toList();
        route.removeStop(stopId, now);
        routes.save(route);
        returnPickups.findByStop(stopId).ifPresent(p -> returnPickups.update(p.unrouted(now)));
        for (String shipmentId : freed) {
            shipments.updateStatus(shipmentId, DeliveryShipmentStatus.AWAITING_ROUTE, null);
        }
        notifyDriver(route, "route.updated", now);
        return route;
    }

    @Override
    @Transactional
    public DeliveryRoute assign(String routeId, String driverId, String vehicleId) {
        access.dispatcher();
        DeliveryRoute route = lock(routeId);
        Instant now = Instant.now(clock);
        String previous = route.driverId();
        route.assign(driverId, vehicleId);
        routes.save(route);
        publishRoute(FulfilmentEvents.ROUTE_ASSIGNED, route, now);
        if (previous != null && !previous.equals(driverId)) {
            feed.publish(new DispatchFeed.Update("route.unassigned", previous, routeId, "NORMAL",
                    Map.of("routeId", routeId), now));
        }
        notifyDriver(route, "route.assigned", now);
        return route;
    }

    @Override
    @Transactional
    public DeliveryRoute cancel(String routeId, String reason) {
        access.dispatcher();
        DeliveryRoute route = lock(routeId);
        Instant now = Instant.now(clock);
        List<String> freed = route.packages().stream()
                .filter(p -> p.status() == PackageStatus.EXPECTED)
                .map(RoutePackage::shipmentId).distinct().toList();
        route.cancel(reason, now);
        routes.save(route);
        for (String shipmentId : freed) {
            shipments.updateStatus(shipmentId, DeliveryShipmentStatus.AWAITING_ROUTE, null);
        }
        publishRoute(FulfilmentEvents.ROUTE_CANCELLED, route, now);
        notifyDriver(route, "route.cancelled", now);
        return route;
    }

    @Override
    @Transactional
    public DeliveryRoute reassignStop(String routeId, String stopId, String targetRouteId) {
        access.dispatcher();
        if (routeId.equals(targetRouteId)) {
            throw new FulfilmentRuleViolation("fulfilment.reassign-same-route", "Choose another route");
        }
        // Lock in id order so two concurrent reassignments never deadlock.
        boolean sourceFirst = routeId.compareTo(targetRouteId) < 0;
        DeliveryRoute first = lock(sourceFirst ? routeId : targetRouteId);
        DeliveryRoute second = lock(sourceFirst ? targetRouteId : routeId);
        DeliveryRoute source = sourceFirst ? first : second;
        DeliveryRoute target = sourceFirst ? second : first;
        if (!target.status().open()) {
            throw new FulfilmentConflict("fulfilment.route-closed", "Route " + targetRouteId + " is "
                    + target.status());
        }
        Instant now = Instant.now(clock);
        RouteStop original = source.stop(stopId);
        List<RoutePackage> moving = source.detachForReassignment(stopId, now);
        Map<String, DeliveryShipment> byId = new HashMap<>();
        for (RoutePackage p : moving) {
            shipments.find(p.shipmentId()).ifPresent(s -> byId.put(s.shipmentId(), s));
        }
        RouteStop added = assembler.addReassigned(target, original, moving, byId, now);
        source.markReassigned(stopId, added.id(), now);
        routes.save(source);
        routes.save(target);
        List<String> shipmentIds = moving.stream().map(RoutePackage::shipmentId).distinct().toList();
        for (String shipmentId : shipmentIds) {
            shipments.updateStatus(shipmentId, DeliveryShipmentStatus.ROUTED, target.id());
        }
        events.publish(FulfilmentEvents.STOP_REASSIGNED, FulfilmentEvents.AGGREGATE, source.id(),
                new FulfilmentEventPayloads.StopEvent(source.id(), stopId, source.driverId(), "DROPOFF",
                        "REASSIGNED", shipmentIds, "Reassigned to " + target.id(), null, target.id(), now));
        feed.publish(new DispatchFeed.Update("stop.reassigned", null, source.id(), "NORMAL",
                Map.of("routeId", source.id(), "stopId", stopId, "targetRouteId", target.id(),
                        "newStopId", added.id(), "shipmentIds", shipmentIds), now));
        notifyDriver(source, "route.updated", now);
        notifyDriver(target, "route.updated", now);
        return target;
    }

    @Override
    @Transactional
    public DeliveryRoute resolveDefect(String defectId, String routeId, String note) {
        Actor by = access.dispatcher();
        Instant now = Instant.now(clock);
        if (!inspections.resolveDefect(defectId, by.subject(), note, now)) {
            throw new NotFoundException("fulfilment.defect-not-found", "Open defect " + defectId + " not found");
        }
        DeliveryRoute route = lock(routeId);
        route.unblock();
        routes.save(route);
        notifyDriver(route, "route.unblocked", now);
        return route;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryShipment> awaitingRoute(String nodeId, int limit) {
        access.opsReader();
        return shipments.awaitingRoute(nodeId, Math.max(1, Math.min(limit, 500)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SosAlert> openSos() {
        access.dispatcher();
        return sos.open();
    }

    @Override
    @Transactional
    public void acknowledgeSos(String alertId) {
        Actor by = access.dispatcher();
        Instant now = Instant.now(clock);
        if (!sos.acknowledge(alertId, by.subject(), now)) {
            throw new NotFoundException("fulfilment.sos-not-found", "Open SOS " + alertId + " not found");
        }
        feed.publish(new DispatchFeed.Update("driver.sos_acknowledged", null, null, "HIGH",
                Map.of("alertId", alertId, "by", by.subject()), now));
    }

    @Override
    @Transactional(readOnly = true)
    public List<InspectionRepository.Defect> openDefects() {
        access.dispatcher();
        return inspections.openDefects();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DriverEventLog.Entry> driverEvents(String routeId, String driverId, int limit) {
        access.dispatcher();
        return log.recent(routeId, driverId, Math.max(1, Math.min(limit, 500)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrdersCommandQueue.Command> forwardFailures(int limit) {
        access.dispatcher();
        return ordersQueue.failures(Math.max(1, Math.min(limit, 200)));
    }

    @Override
    public DriverAccess suspendDriver(String driverId, String reason) {
        return driverAccess.suspend(driverId, reason);
    }

    @Override
    public DriverAccess reinstateDriver(String driverId) {
        return driverAccess.reinstate(driverId);
    }

    @Override
    public String authorizeStream() {
        return access.dispatcher().subject();
    }

    // ------------------------------------------------------------------ helpers

    private void attach(DeliveryRoute route, List<String> shipmentIds, Instant now) {
        List<DeliveryShipment> locked = shipments.lockAll(shipmentIds);
        if (locked.size() != shipmentIds.stream().distinct().count()) {
            throw new NotFoundException("fulfilment.shipment-not-found", "Unknown shipment in " + shipmentIds);
        }
        for (DeliveryShipment s : locked) {
            if (s.status() != DeliveryShipmentStatus.AWAITING_ROUTE) {
                throw new FulfilmentConflict("fulfilment.shipment-not-routable", "Shipment " + s.shipmentId()
                        + " is " + s.status() + (s.routeId() == null ? "" : " on " + s.routeId()));
            }
            if (s.origin() == PackageOrigin.FC && s.nodeId() != null && !s.nodeId().equals(route.depotNodeId())) {
                throw new FulfilmentRuleViolation("fulfilment.wrong-depot", "Shipment " + s.shipmentId()
                        + " ships from " + s.nodeId() + ", not " + route.depotNodeId());
            }
        }
        // Keep the caller's order (the planner sorted drop-offs by ZIP); the lock query sorts by id.
        List<String> order = shipmentIds.stream().distinct().toList();
        List<DeliveryShipment> ordered = locked.stream()
                .sorted(Comparator.comparingInt((DeliveryShipment s) -> order.indexOf(s.shipmentId()))).toList();
        List<String> added = assembler.add(route, ordered, now);
        routes.save(route);
        for (String shipmentId : added) {
            shipments.updateStatus(shipmentId, DeliveryShipmentStatus.ROUTED, route.id());
        }
    }

    private String zoneOf(DeliveryShipment s, Map<String, Optional<String>> zoneByZip) {
        String zip = s.postalCode() == null ? "" : s.postalCode();
        Optional<String> zone;
        if (s.location() != null) {
            zone = geo.deliveryZoneAt(s.location());
        } else {
            zone = zoneByZip.computeIfAbsent(zip, z -> {
                Optional<GeoPoint> point = z.isBlank() ? Optional.empty() : geo.locateZip(z);
                return point.flatMap(geo::deliveryZoneAt);
            });
        }
        return zone.orElse(zip.isBlank() ? "UNZONED" : "ZIP " + zip);
    }

    private void requireCentre(String nodeId) {
        if (nodeId == null || centres.find(nodeId).isEmpty()) {
            throw new NotFoundException("fulfilment.centre-not-found", "Depot " + nodeId + " not found");
        }
    }

    private DeliveryRoute lock(String routeId) {
        return routes.lock(routeId).orElseThrow(() -> routeNotFound(routeId));
    }

    private void publishRoute(String type, DeliveryRoute r, Instant now) {
        events.publish(type, FulfilmentEvents.AGGREGATE, r.id(), new FulfilmentEventPayloads.RouteEvent(r.id(),
                r.zone(), r.serviceDate(), r.depotNodeId(), r.driverId(), r.vehicleId(), r.status().name(),
                r.stops().size(), r.packages().size(), now));
        feed.publish(new DispatchFeed.Update("route.updated", null, r.id(), "NORMAL",
                Map.of("routeId", r.id(), "status", r.status().name(), "type", type), now));
    }

    private void notifyDriver(DeliveryRoute r, String name, Instant now) {
        if (r.driverId() != null) {
            feed.publish(new DispatchFeed.Update(name, r.driverId(), r.id(), "NORMAL",
                    Map.of("routeId", r.id(), "status", r.status().name()), now));
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static NotFoundException routeNotFound(String routeId) {
        return new NotFoundException("fulfilment.route-not-found", "Route " + routeId + " not found");
    }
}
