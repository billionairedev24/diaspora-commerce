package com.afrotransact.fulfilment.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.FulfilmentConflict;
import com.afrotransact.fulfilment.domain.FulfilmentRuleViolation;
import com.afrotransact.fulfilment.domain.GeoPoint;
import com.afrotransact.fulfilment.domain.ReturnPickup;
import com.afrotransact.fulfilment.domain.ReturnPickupStatus;
import com.afrotransact.fulfilment.domain.RouteStatus;
import com.afrotransact.fulfilment.domain.RouteStop;
import com.afrotransact.fulfilment.domain.StopKind;
import com.afrotransact.fulfilment.port.DispatchFeed;
import com.afrotransact.fulfilment.port.ReturnPickupRepository;
import com.afrotransact.fulfilment.port.RouteRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReturnPickupServiceImpl implements ReturnPickupService {

    private final ReturnPickupRepository pickups;
    private final RouteRepository routes;
    private final FulfilmentAccess access;
    private final DomainEventPublisher events;
    private final DispatchFeed feed;
    private final String defaultNode;
    private final Clock clock;

    ReturnPickupServiceImpl(ReturnPickupRepository pickups, RouteRepository routes, FulfilmentAccess access,
                            DomainEventPublisher events, DispatchFeed feed,
                            @Value("${at.fulfilment.default-node:}") String defaultNode, Clock clock) {
        this.pickups = pickups;
        this.routes = routes;
        this.access = access;
        this.events = events;
        this.feed = feed;
        this.defaultNode = defaultNode == null || defaultNode.isBlank() ? null : defaultNode.trim();
        this.clock = clock;
    }

    @Override
    @Transactional
    public Result schedule(ScheduleReturnPickupCommand c) {
        access.returnScheduler();
        if (c.rmaId() == null || c.rmaId().isBlank() || c.rmaNumber() == null || c.rmaNumber().isBlank()) {
            throw new FulfilmentRuleViolation("fulfilment.rma-required", "rmaId and rmaNumber are required");
        }
        Optional<ReturnPickup> existing = pickups.findByRma(c.rmaId().trim());
        if (existing.isPresent()) {
            return new Result(existing.get(), false);
        }
        if (c.address() == null) {
            throw new FulfilmentRuleViolation("fulfilment.return-address-required", "address is required");
        }
        Instant now = Instant.now(clock);
        ScheduleReturnPickupCommand.Address a = c.address();
        GeoPoint location;
        try {
            location = GeoPoint.of(a.lat(), a.lon());
        } catch (FulfilmentRuleViolation invalid) {
            location = null;
        }
        String depot = c.depotNodeId() == null || c.depotNodeId().isBlank() ? defaultNode
                : c.depotNodeId().trim().toUpperCase(Locale.ROOT);
        LocalDate readyFrom = c.readyFrom() == null ? LocalDate.now(clock) : c.readyFrom();
        ReturnPickup pickup = new ReturnPickup(UUID.randomUUID().toString(), c.rmaId().trim(), c.rmaNumber().trim(),
                c.orderId(), c.orderNumber(), c.shipmentId(), c.customerId(), c.sellerOrgId(), a.name(), a.phone(),
                a.line1(), a.line2(), a.city(), a.state(), a.postalCode(),
                a.country() == null || a.country().isBlank() ? "US" : a.country(), location, depot, readyFrom,
                c.collectBy(), c.units() <= 0 ? 1 : c.units(), ReturnPickupStatus.REQUESTED, null, null, null, now,
                now);
        if (!pickups.insert(pickup)) {
            return new Result(pickups.findByRma(pickup.rmaId()).orElseThrow(), false);
        }
        publish(FulfilmentEvents.RETURN_PICKUP_SCHEDULED, pickup, now);
        feed.publish(new DispatchFeed.Update("return_pickup.scheduled", null, null, "NORMAL",
                Map.of("returnPickupId", pickup.id(), "rmaNumber", pickup.rmaNumber()), now));
        return new Result(pickup, true);
    }

    @Override
    @Transactional(readOnly = true)
    public ReturnPickup get(String id) {
        access.returnReader();
        return pickups.find(id).orElseThrow(() -> notFound(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReturnPickup> list(ReturnPickupStatus status, String depotNodeId, int limit) {
        access.dispatcher();
        return pickups.list(status, depotNodeId == null || depotNodeId.isBlank() ? null
                : depotNodeId.trim().toUpperCase(Locale.ROOT), Math.clamp(limit, 1, 200));
    }

    @Override
    @Transactional
    public ReturnPickup cancel(String id, String reason) {
        access.returnScheduler();
        ReturnPickup p = pickups.lock(id).orElseThrow(() -> notFound(id));
        Instant now = Instant.now(clock);
        if (p.status() == ReturnPickupStatus.CANCELLED) {
            return p;
        }
        String why = reason == null || reason.isBlank() ? "Return cancelled" : reason.trim();
        if (p.status() == ReturnPickupStatus.ROUTED && p.routeId() != null) {
            DeliveryRoute route = routes.lock(p.routeId()).orElse(null);
            if (route != null && route.status() != RouteStatus.COMPLETED && route.status() != RouteStatus.CANCELLED) {
                route.cancelReturnStop(p.stopId(), why, now);
                routes.save(route);
                feed.publish(new DispatchFeed.Update("route.updated", route.driverId(), route.id(), "NORMAL",
                        Map.of("routeId", route.id(), "cancelledStopId", p.stopId()), now));
            }
        }
        ReturnPickup cancelled = p.cancelled(why, now);
        pickups.update(cancelled);
        publish(FulfilmentEvents.RETURN_PICKUP_CANCELLED, cancelled, now);
        return cancelled;
    }

    @Override
    @Transactional
    public DeliveryRoute addToRoute(String routeId, List<String> pickupIds) {
        access.dispatcher();
        if (pickupIds == null || pickupIds.isEmpty()) {
            throw new FulfilmentRuleViolation("fulfilment.return-pickups-required", "Choose at least one return");
        }
        DeliveryRoute route = routes.lock(routeId).orElseThrow(() -> new NotFoundException(
                "fulfilment.route-not-found", "Route " + routeId + " not found"));
        Instant now = Instant.now(clock);
        for (String id : pickupIds.stream().distinct().toList()) {
            ReturnPickup p = pickups.lock(id).orElseThrow(() -> notFound(id));
            if (p.status() != ReturnPickupStatus.REQUESTED) {
                throw new FulfilmentConflict("fulfilment.return-pickup-state", "Return " + p.rmaNumber() + " is "
                        + p.status());
            }
            RouteStop stop = RouteStop.pending(UUID.randomUUID().toString(), StopKind.RETURN_PICKUP, 0,
                    "Return " + p.rmaNumber() + (p.name() == null ? "" : " · " + p.name()), p.sellerOrgId(),
                    p.orderNumber(), p.addressLine(), p.city(), p.postalCode(), p.location(),
                    p.collectBy() == null ? null : "by " + p.collectBy(), true, false, now);
            route.addReturnPickupStop(stop);
            ReturnPickup routed = p.routed(route.id(), stop.id(), now);
            pickups.update(routed);
            publish(FulfilmentEvents.RETURN_PICKUP_ROUTED, routed, now);
        }
        routes.save(route);
        feed.publish(new DispatchFeed.Update("route.updated", route.driverId(), route.id(), "NORMAL",
                Map.of("routeId", route.id()), now));
        return route;
    }

    private void publish(String type, ReturnPickup p, Instant at) {
        events.publish(type, FulfilmentEvents.AGGREGATE, p.rmaId(), FulfilmentEventPayloads.ReturnPickupEvent.of(p,
                at));
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("fulfilment.return-pickup-not-found", "Return pickup " + id + " not found");
    }
}
