package com.afrotransact.fulfilment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.DeliveryShipment;
import com.afrotransact.fulfilment.domain.DeliveryShipmentStatus;
import com.afrotransact.fulfilment.domain.FulfilmentTask;
import com.afrotransact.fulfilment.domain.GeoPoint;
import com.afrotransact.fulfilment.domain.PackageOrigin;
import com.afrotransact.fulfilment.domain.RoutePackage;
import com.afrotransact.fulfilment.domain.RouteStatus;
import com.afrotransact.fulfilment.domain.TaskLine;
import com.afrotransact.fulfilment.domain.TaskStatus;
import com.afrotransact.fulfilment.port.DeliveryShipmentRepository;
import com.afrotransact.fulfilment.port.DispatchFeed;
import com.afrotransact.fulfilment.port.IdIssuer;
import com.afrotransact.fulfilment.port.InventoryClient;
import com.afrotransact.fulfilment.port.InventoryClient.ReservedLine;
import com.afrotransact.fulfilment.port.RouteRepository;
import com.afrotransact.fulfilment.port.TaskRepository;
import com.afrotransact.fulfilment.port.UpstreamFailure;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ShipmentIntakeServiceImpl implements ShipmentIntakeService {

    static final String CONSUMER = "fulfilment.orders";

    private static final Logger LOG = LoggerFactory.getLogger(ShipmentIntakeServiceImpl.class);

    private final InboxDeduplicator inbox;
    private final TaskRepository tasks;
    private final DeliveryShipmentRepository shipments;
    private final RouteRepository routes;
    private final InventoryClient inventory;
    private final IdIssuer ids;
    private final DomainEventPublisher events;
    private final DispatchFeed feed;
    private final PickupAssignmentService pickups;
    private final Clock clock;
    private final String defaultNode;

    ShipmentIntakeServiceImpl(InboxDeduplicator inbox, TaskRepository tasks, DeliveryShipmentRepository shipments,
                              RouteRepository routes, InventoryClient inventory, IdIssuer ids,
                              DomainEventPublisher events, DispatchFeed feed, PickupAssignmentService pickups,
                              Clock clock, @Value("${at.fulfilment.default-node:}") String defaultNode) {
        this.inbox = inbox;
        this.tasks = tasks;
        this.shipments = shipments;
        this.routes = routes;
        this.inventory = inventory;
        this.ids = ids;
        this.events = events;
        this.feed = feed;
        this.pickups = pickups;
        this.clock = clock;
        this.defaultNode = defaultNode == null || defaultNode.isBlank() ? null : defaultNode;
    }

    @Override
    @Transactional
    public int orderPlaced(UUID eventId, PlacedOrder order) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return 0;
        }
        Instant now = Instant.now(clock);
        boolean anyFc = order.shipments().stream().anyMatch(PlacedOrder.PlacedShipment::afrotransactFulfilled);
        Map<String, String> nodeBySku = anyFc ? reservedNodes(order.orderId()) : Map.of();
        int created = 0;
        for (PlacedOrder.PlacedShipment s : order.shipments()) {
            boolean fc = s.afrotransactFulfilled();
            if (!fc && !s.afrotransactDelivery()) {
                continue;
            }
            String label = ids.packageLabel();
            String node = fc ? nodeOf(s, nodeBySku) : null;
            if (fc) {
                List<TaskLine> lines = s.lines().stream().map(l -> new TaskLine(l.lineId(), l.variantAtin(),
                        l.title(), l.quantity(), 0, null)).toList();
                FulfilmentTask task = new FulfilmentTask(UUID.randomUUID().toString(), s.shipmentId(),
                        order.orderId(), order.orderNumber(), s.fulfilmentType(), s.deliveryMethod(), node,
                        TaskStatus.PICK_PENDING, lines, label, null, null, null, null, null, now, 0);
                if (tasks.insert(task)) {
                    created++;
                    events.publish(FulfilmentEvents.TASK_CREATED, FulfilmentEvents.AGGREGATE, s.shipmentId(),
                            new FulfilmentEventPayloads.TaskEvent(task.id(), s.shipmentId(), order.orderId(),
                                    order.orderNumber(), node, task.status().name(), label, null, now));
                }
            }
            if (s.afrotransactDelivery()) {
                int units = s.lines().stream().mapToInt(PlacedOrder.PlacedLine::quantity).sum();
                PlacedOrder.SellerPickup pickup = fc ? null : s.pickup();
                DeliveryShipment ds = new DeliveryShipment(s.shipmentId(), order.orderId(), order.orderNumber(),
                        order.customerId(), s.sellerOrgId(), s.sellerName(), s.fulfilmentType(),
                        fc ? PackageOrigin.FC : PackageOrigin.SELLER, node, label, order.shipToCity(),
                        order.shipToState(), zip5(order.shipToPostalCode()), order.shipToCountry(),
                        GeoPoint.of(order.shipToLat(), order.shipToLon()), Boolean.TRUE.equals(s.perishable()),
                        s.handoverRequired(), units, DeliveryShipmentStatus.AWAITING_ROUTE, null, s.promisedBy(),
                        now, s.shipBy(), pickup == null ? null : pickup.locationId(),
                        pickup == null ? null : GeoPoint.of(pickup.lat(), pickup.lng()));
                if (shipments.insert(ds)) {
                    created++;
                    // S255: a seller pickup goes to the nearest available driver; else it waits for the route builder.
                    if (ds.origin() == PackageOrigin.SELLER) {
                        pickups.autoAssign(ds);
                    }
                }
            }
        }
        LOG.info("Order {} placed: {} fulfilment records created", order.orderNumber(), created);
        return created;
    }

    @Override
    @Transactional
    public boolean shipmentCancelled(UUID eventId, String shipmentId, String reason) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return false;
        }
        Instant now = Instant.now(clock);
        boolean changed = false;
        Optional<FulfilmentTask> task = tasks.findByShipment(shipmentId).flatMap(t -> tasks.lock(t.id()));
        if (task.isPresent() && task.get().cancel(reason, now)) {
            tasks.update(task.get());
            FulfilmentTask t = task.get();
            events.publish(FulfilmentEvents.TASK_CANCELLED, FulfilmentEvents.AGGREGATE, shipmentId,
                    new FulfilmentEventPayloads.TaskEvent(t.id(), shipmentId, t.orderId(), t.orderNumber(),
                            t.nodeId(), t.status().name(), t.packageLabel(), reason, now));
            changed = true;
        }
        Optional<DeliveryShipment> ds = shipments.find(shipmentId);
        if (ds.isPresent() && ds.get().status() != DeliveryShipmentStatus.CANCELLED) {
            shipments.updateStatus(shipmentId, DeliveryShipmentStatus.CANCELLED, ds.get().routeId());
            changed = true;
            if (ds.get().routeId() != null) {
                pullFromRoute(ds.get().routeId(), shipmentId, reason, now);
            }
        }
        return changed;
    }

    /** Takes a cancelled shipment off a route that has not started; started routes alert dispatch. */
    private void pullFromRoute(String routeId, String shipmentId, String reason, Instant now) {
        Optional<DeliveryRoute> locked = routes.lock(routeId);
        if (locked.isEmpty()) {
            return;
        }
        DeliveryRoute route = locked.get();
        Optional<RoutePackage> pkg = route.packages().stream()
                .filter(p -> p.shipmentId().equals(shipmentId) && p.status().active()).findFirst();
        if (pkg.isEmpty()) {
            return;
        }
        String stopId = pkg.get().dropoffStopId();
        boolean onlyThisShipment = route.packagesForDrop(stopId).stream()
                .allMatch(p -> p.shipmentId().equals(shipmentId));
        if ((route.status() == RouteStatus.PLANNED || route.status() == RouteStatus.ASSIGNED) && onlyThisShipment) {
            route.removeStop(stopId, now);
            routes.save(route);
        }
        feed.publish(new DispatchFeed.Update("shipment.cancelled", route.driverId(), routeId, "NORMAL",
                Map.of("shipmentId", shipmentId, "stopId", stopId, "routeStatus", route.status().name(),
                        "reason", reason == null ? "" : reason), now));
    }

    private Map<String, String> reservedNodes(String orderId) {
        Map<String, String> out = new HashMap<>();
        try {
            for (ReservedLine l : inventory.reservation(orderId).orElse(List.of())) {
                if (l.sku() != null && l.nodeId() != null) {
                    out.putIfAbsent(l.sku().toUpperCase(Locale.ROOT), l.nodeId());
                }
            }
        } catch (UpstreamFailure f) {
            LOG.warn("Reservation of order {} unavailable ({}); tasks wait for a node assignment", orderId,
                    f.getMessage());
        }
        return out;
    }

    private String nodeOf(PlacedOrder.PlacedShipment s, Map<String, String> nodeBySku) {
        if (s.nodeId() != null && !s.nodeId().isBlank()) {
            return s.nodeId();
        }
        return s.lines().stream().map(l -> l.variantAtin() == null ? null
                        : nodeBySku.get(l.variantAtin().toUpperCase(Locale.ROOT)))
                .filter(n -> n != null).findFirst().orElse(defaultNode);
    }

    private static String zip5(String postalCode) {
        if (postalCode == null) {
            return null;
        }
        String p = postalCode.trim();
        return p.length() > 5 && p.charAt(5) == '-' ? p.substring(0, 5) : p;
    }
}
