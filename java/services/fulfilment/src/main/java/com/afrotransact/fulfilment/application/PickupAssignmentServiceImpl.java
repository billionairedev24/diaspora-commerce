package com.afrotransact.fulfilment.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.DeliveryShipment;
import com.afrotransact.fulfilment.domain.DeliveryShipmentStatus;
import com.afrotransact.fulfilment.domain.DriverSuggestion;
import com.afrotransact.fulfilment.domain.FulfilmentConflict;
import com.afrotransact.fulfilment.domain.FulfilmentRuleViolation;
import com.afrotransact.fulfilment.domain.GeoPoint;
import com.afrotransact.fulfilment.domain.PackageOrigin;
import com.afrotransact.fulfilment.domain.PackageStatus;
import com.afrotransact.fulfilment.domain.RoutePackage;
import com.afrotransact.fulfilment.port.DeliveryShipmentRepository;
import com.afrotransact.fulfilment.port.DispatchFeed;
import com.afrotransact.fulfilment.port.DriverPositionRepository;
import com.afrotransact.fulfilment.port.GeoClient;
import com.afrotransact.fulfilment.port.PickupAssignmentLog;
import com.afrotransact.fulfilment.port.RouteRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PickupAssignmentServiceImpl implements PickupAssignmentService {

    static final String AUTO = "AUTO_NEAREST";
    static final String OVERRIDE = "DISPATCH_OVERRIDE";
    private static final String SYSTEM = "system:nearest-driver";
    private static final Logger LOG = LoggerFactory.getLogger(PickupAssignmentServiceImpl.class);

    private final FulfilmentAccess access;
    private final DeliveryShipmentRepository shipments;
    private final RouteRepository routes;
    private final DriverPositionRepository positions;
    private final PickupAssignmentLog log;
    private final GeoClient geo;
    private final RouteAssembler assembler;
    private final DomainEventPublisher events;
    private final DispatchFeed feed;
    private final NearestDriverSettings settings;
    private final Clock clock;

    PickupAssignmentServiceImpl(FulfilmentAccess access, DeliveryShipmentRepository shipments, RouteRepository routes,
                                DriverPositionRepository positions, PickupAssignmentLog log, GeoClient geo,
                                RouteAssembler assembler, DomainEventPublisher events, DispatchFeed feed,
                                NearestDriverSettings settings, Clock clock) {
        this.access = access;
        this.shipments = shipments;
        this.routes = routes;
        this.positions = positions;
        this.log = log;
        this.geo = geo;
        this.assembler = assembler;
        this.events = events;
        this.feed = feed;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DriverSuggestion> suggestions(String shipmentId) {
        access.dispatcher();
        DeliveryShipment s = shipments.find(shipmentId).orElseThrow(() -> notFound(shipmentId));
        requireSellerPickup(s);
        return rank(s);
    }

    @Override
    public Optional<DeliveryRoute> autoAssign(DeliveryShipment s) {
        if (!settings.enabled() || s.origin() != PackageOrigin.SELLER || s.pickupPoint() == null
                || s.status() != DeliveryShipmentStatus.AWAITING_ROUTE) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        for (DriverSuggestion best : rank(s)) {
            Optional<DeliveryRoute> locked = routes.lock(best.routeId());
            if (locked.isEmpty() || !locked.get().status().open() || !best.driverId().equals(locked.get().driverId())) {
                continue;
            }
            DeliveryRoute route = locked.get();
            assembler.add(route, List.of(s), now);
            routes.save(route);
            shipments.updateStatus(s.shipmentId(), DeliveryShipmentStatus.ROUTED, route.id());
            record(s, route, AUTO, null, null, best, SYSTEM, now);
            LOG.info("Seller pickup {} auto-assigned to driver {} on {} ({} mi)", s.shipmentId(), best.driverId(),
                    route.id(), best.rankMiles());
            return Optional.of(route);
        }
        return Optional.empty();
    }

    @Override
    @Transactional
    public DeliveryRoute assign(String shipmentId, String routeId, String reason) {
        String actor = access.dispatcher().subject();
        if (reason == null || reason.trim().length() < 5) {
            throw new FulfilmentRuleViolation("fulfilment.reason-required",
                    "Say why you override the nearest-driver assignment (5+ characters)");
        }
        DeliveryShipment s = shipments.lockAll(List.of(shipmentId)).stream().findFirst()
                .orElseThrow(() -> notFound(shipmentId));
        requireSellerPickup(s);
        if (routeId.equals(s.routeId())) {
            throw new FulfilmentConflict("fulfilment.already-on-route", "Pickup " + shipmentId + " is already on "
                    + routeId);
        }
        if (s.status() != DeliveryShipmentStatus.AWAITING_ROUTE && s.status() != DeliveryShipmentStatus.ROUTED) {
            throw new FulfilmentConflict("fulfilment.pickup-not-movable", "Pickup " + shipmentId + " is "
                    + s.status());
        }
        Instant now = Instant.now(clock);
        String previous = s.routeId();
        // Lock both routes in id order so concurrent overrides never deadlock.
        List<String> ids = new ArrayList<>(List.of(routeId));
        if (previous != null) {
            ids.add(previous);
        }
        ids.sort(Comparator.naturalOrder());
        Map<String, DeliveryRoute> locked = new HashMap<>();
        for (String id : ids) {
            locked.put(id, routes.lock(id).orElseThrow(() -> new NotFoundException("fulfilment.route-not-found",
                    "Route " + id + " not found")));
        }
        DeliveryRoute target = locked.get(routeId);
        if (!target.status().open()) {
            throw new FulfilmentConflict("fulfilment.route-closed", "Route " + routeId + " is " + target.status());
        }
        if (previous != null) {
            detach(locked.get(previous), shipmentId, now);
        }
        assembler.add(target, List.of(s), now);
        routes.save(target);
        shipments.updateStatus(shipmentId, DeliveryShipmentStatus.ROUTED, target.id());
        record(s, target, OVERRIDE, reason.trim(), previous, null, actor, now);
        return target;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PickupAssignmentLog.Entry> history(String shipmentId) {
        access.dispatcher();
        return log.forShipment(shipmentId);
    }

    /** Nearest available drivers by KNN, re-ranked by geo road distance (straight line when geo is down); top 5. */
    private List<DriverSuggestion> rank(DeliveryShipment s) {
        GeoPoint pickup = s.pickupPoint();
        if (pickup == null) {
            return List.of();
        }
        Optional<String> zone = geo.deliveryZoneAt(pickup);
        if (zone.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock.withZone(settings.zone()));
        List<DriverPositionRepository.Candidate> candidates = positions.nearestAvailable(pickup, zone.get(), today,
                now.minus(settings.staleAfter()), settings.maxPackagesPerRoute(), settings.candidates());
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Optional<GeoClient.RoadDistance>> roads = geo.roadDistances(pickup,
                candidates.stream().map(DriverPositionRepository.Candidate::position).toList());
        List<DriverSuggestion> out = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            DriverPositionRepository.Candidate c = candidates.get(i);
            Optional<GeoClient.RoadDistance> road = i < roads.size() ? roads.get(i) : Optional.empty();
            out.add(new DriverSuggestion(c.driverId(), c.routeId(), c.routeName(), c.zone(), c.routeStatus(),
                    Math.round(c.metres() / 1609.344 * 100) / 100.0,
                    road.map(GeoClient.RoadDistance::roadMiles).orElse(null),
                    road.map(GeoClient.RoadDistance::durationMinutes).orElse(null),
                    road.map(GeoClient.RoadDistance::method).orElse("STRAIGHT_LINE"), c.positionAt(),
                    c.activePackages(), settings.maxPackagesPerRoute() - c.activePackages()));
        }
        out.sort(Comparator.comparingDouble(DriverSuggestion::rankMiles)
                .thenComparingDouble(DriverSuggestion::straightLineMiles).thenComparing(DriverSuggestion::driverId));
        return List.copyOf(out.subList(0, Math.min(NearestDriverSettings.SUGGESTIONS, out.size())));
    }

    /** Takes a not-yet-collected pickup off its current route (its drop-off must carry only this shipment). */
    private void detach(DeliveryRoute from, String shipmentId, Instant now) {
        RoutePackage pkg = from.packages().stream()
                .filter(p -> p.shipmentId().equals(shipmentId) && p.status().active()).findFirst().orElse(null);
        if (pkg == null) {
            return;
        }
        boolean alone = from.packagesForDrop(pkg.dropoffStopId()).stream()
                .allMatch(p -> p.shipmentId().equals(shipmentId));
        if (pkg.status() != PackageStatus.EXPECTED || !alone) {
            throw new FulfilmentConflict("fulfilment.pickup-not-movable", "Pickup " + shipmentId + " can't leave "
                    + from.id() + ": it was collected already or shares its drop-off; reassign the stop instead");
        }
        from.removeStop(pkg.dropoffStopId(), now);
        routes.save(from);
        if (from.driverId() != null) {
            feed.publish(new DispatchFeed.Update("route.updated", from.driverId(), from.id(), "NORMAL",
                    Map.of("routeId", from.id(), "status", from.status().name()), now));
        }
    }

    private void record(DeliveryShipment s, DeliveryRoute route, String mode, String reason, String previous,
                        DriverSuggestion best, String actor, Instant now) {
        log.record(new PickupAssignmentLog.Entry(UUID.randomUUID(), s.shipmentId(), route.id(), route.driverId(), mode,
                reason, previous, best == null ? null : best.straightLineMiles(),
                best == null ? null : best.roadMiles(), best == null ? null : best.distanceMethod(), actor, now));
        events.publish(AUTO.equals(mode) ? FulfilmentEvents.PICKUP_AUTO_ASSIGNED
                        : FulfilmentEvents.PICKUP_DISPATCH_ASSIGNED, FulfilmentEvents.AGGREGATE, s.shipmentId(),
                new FulfilmentEventPayloads.PickupAssigned(s.shipmentId(), s.orderId(), s.orderNumber(),
                        s.sellerOrgId(), s.pickupLocationId(), route.id(), route.driverId(), mode, reason, previous,
                        best == null ? null : best.straightLineMiles(), best == null ? null : best.roadMiles(),
                        best == null ? null : best.durationMinutes(), best == null ? null : best.distanceMethod(),
                        now));
        feed.publish(new DispatchFeed.Update(AUTO.equals(mode) ? "pickup.auto_assigned" : "pickup.dispatch_assigned",
                null, route.id(), "NORMAL", Map.of("shipmentId", s.shipmentId(), "routeId", route.id(),
                "driverId", route.driverId() == null ? "" : route.driverId(), "mode", mode), now));
        if (route.driverId() != null) {
            feed.publish(new DispatchFeed.Update("route.updated", route.driverId(), route.id(), "NORMAL",
                    Map.of("routeId", route.id(), "status", route.status().name()), now));
        }
    }

    private static void requireSellerPickup(DeliveryShipment s) {
        if (s.origin() != PackageOrigin.SELLER) {
            throw new FulfilmentRuleViolation("fulfilment.not-a-seller-pickup", "Shipment " + s.shipmentId()
                    + " leaves from a fulfilment centre, not a seller's store");
        }
    }

    private static NotFoundException notFound(String shipmentId) {
        return new NotFoundException("fulfilment.shipment-not-found", "Shipment " + shipmentId + " not found");
    }
}
