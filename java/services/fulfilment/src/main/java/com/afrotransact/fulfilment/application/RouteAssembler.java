package com.afrotransact.fulfilment.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.DeliveryShipment;
import com.afrotransact.fulfilment.domain.PackageOrigin;
import com.afrotransact.fulfilment.domain.PackageStatus;
import com.afrotransact.fulfilment.domain.RoutePackage;
import com.afrotransact.fulfilment.domain.RunBag;
import com.afrotransact.fulfilment.domain.RunDispatch;
import com.afrotransact.fulfilment.domain.RunDrop;
import com.afrotransact.fulfilment.domain.RouteStop;
import com.afrotransact.fulfilment.domain.StopKind;
import com.afrotransact.fulfilment.port.IdIssuer;
import org.springframework.stereotype.Component;

/**
 * Turns dispatch-pool shipments into stops and packages: one drop-off per order (same address), one pickup per
 * seller ship-from location (S259) for seller-origin packages (visited before the drop-offs), one package per shipment with its ATS label.
 */
@Component
class RouteAssembler {

    private final IdIssuer ids;

    RouteAssembler(IdIssuer ids) {
        this.ids = ids;
    }

    /** @return the shipment ids added */
    List<String> add(DeliveryRoute route, List<DeliveryShipment> shipments, Instant now) {
        Map<String, List<DeliveryShipment>> byOrder = new LinkedHashMap<>();
        for (DeliveryShipment s : shipments) {
            byOrder.computeIfAbsent(s.orderNumber(), o -> new ArrayList<>()).add(s);
        }
        List<String> added = new ArrayList<>();
        for (List<DeliveryShipment> group : byOrder.values()) {
            DeliveryShipment first = group.getFirst();
            boolean handover = group.stream().anyMatch(DeliveryShipment::handoverRequired);
            boolean perishable = group.stream().anyMatch(DeliveryShipment::perishable);
            RouteStop drop = RouteStop.pending(UUID.randomUUID().toString(), StopKind.DROPOFF, 0,
                    "Order " + first.orderNumber(), null, first.orderNumber(), null, first.city(),
                    first.postalCode(), first.location(), route.window(), handover, !handover && !perishable, now);
            List<RoutePackage> pkgs = new ArrayList<>();
            for (DeliveryShipment s : group) {
                String pickupStopId = null;
                if (s.origin() == PackageOrigin.SELLER) {
                    pickupStopId = pickupStop(route, s, now).id();
                }
                pkgs.add(new RoutePackage(UUID.randomUUID().toString(), s.label() == null ? ids.packageLabel()
                        : s.label(), s.shipmentId(), s.orderNumber(), s.origin(), pickupStopId, drop.id(),
                        s.perishable(), PackageStatus.EXPECTED, false, false, false, now));
                added.add(s.shipmentId());
            }
            route.addStop(drop, pkgs);
        }
        return added;
    }

    /**
     * Adds a run's drops to a fresh route (S264): the sellers of the chunk are visited first (one pickup stop per
     * seller, by name), then each order is one drop-off in the given order, at its address or, for a pickup-point
     * run, at the pickup point with the customer's handover code required. A package is one seller's bag of an order.
     *
     * @param drops        this route's drops, in visiting order
     * @param byShipment   the drops' shipments
     * @param bags         bags per shipment (a shipment without any is one package under its own label)
     * @param run          the run (pickup point, drop mode)
     * @param atDepot      sellers whose goods are already at the depot: loaded at the FC, not collected
     * @return the shipment ids added
     */
    List<String> addRun(DeliveryRoute route, List<RunDrop> drops, Map<String, DeliveryShipment> byShipment,
                        Map<String, List<RunBag>> bags, RunDispatch run, Predicate<String> atDepot, Instant now) {
        // Sellers sort by name; a new pickup is placed first, so adding them in reverse leaves them in order.
        Map<String, RunBag> sellers = new HashMap<>();
        for (RunDrop d : drops) {
            for (String shipmentId : d.shipmentIds()) {
                for (RunBag b : bagsOf(byShipment.get(shipmentId), bags)) {
                    if (!atDepot.test(b.sellerOrgId())) {
                        sellers.putIfAbsent(b.sellerOrgId(), b);
                    }
                }
            }
        }
        List<RunBag> byName = new ArrayList<>(sellers.values());
        byName.sort(Comparator.comparing(RouteAssembler::sellerName).thenComparing(RunBag::sellerOrgId));
        Collections.reverse(byName);
        Map<String, RouteStop> pickups = new HashMap<>();
        for (RunBag b : byName) {
            RouteStop pickup = RouteStop.pendingPickup(UUID.randomUUID().toString(), sellerName(b), b.sellerOrgId(),
                    null, null, route.window(), now);
            route.addPickupStop(pickup);
            pickups.put(b.sellerOrgId(), pickup);
        }
        List<String> added = new ArrayList<>();
        for (RunDrop d : drops) {
            List<DeliveryShipment> mine = d.shipmentIds().stream().map(byShipment::get).toList();
            boolean handover = run.doorstep() ? mine.stream().anyMatch(DeliveryShipment::handoverRequired) : true;
            boolean perishable = mine.stream().anyMatch(DeliveryShipment::perishable);
            DeliveryShipment first = mine.getFirst();
            RouteStop drop = run.doorstep()
                    ? RouteStop.pending(UUID.randomUUID().toString(), StopKind.DROPOFF, 0, "Order " + d.orderNumber(),
                    null, d.orderNumber(), null, first.city(), first.postalCode(), first.location(), route.window(),
                    handover, !handover && !perishable, now)
                    : RouteStop.pending(UUID.randomUUID().toString(), StopKind.DROPOFF, 0,
                    "Order " + d.orderNumber() + " · " + run.pickupPoint().name(), null, d.orderNumber(),
                    run.pickupPoint().display(), run.pickupPoint().city(), run.pickupPoint().postalCode(), null,
                    route.window(), true, false, now);
            List<RoutePackage> pkgs = new ArrayList<>();
            for (DeliveryShipment s : mine) {
                for (RunBag b : bagsOf(s, bags)) {
                    boolean depot = atDepot.test(b.sellerOrgId());
                    pkgs.add(new RoutePackage(UUID.randomUUID().toString(), b.label(), s.shipmentId(),
                            s.orderNumber(), depot ? PackageOrigin.FC : PackageOrigin.SELLER,
                            depot ? null : pickups.get(b.sellerOrgId()).id(), drop.id(), s.perishable(),
                            PackageStatus.EXPECTED, false, false, false, now));
                }
                added.add(s.shipmentId());
            }
            route.addStop(drop, pkgs);
        }
        return added;
    }

    /** The bags of a shipment; one package under the shipment's own label when none were recorded. */
    private static List<RunBag> bagsOf(DeliveryShipment s, Map<String, List<RunBag>> bags) {
        List<RunBag> mine = bags.get(s.shipmentId());
        if (mine != null && !mine.isEmpty()) {
            return mine;
        }
        return List.of(new RunBag(s.shipmentId(), s.sellerOrgId() == null ? "unknown" : s.sellerOrgId(),
                s.sellerName(), s.label(), s.units()));
    }

    private static String sellerName(RunBag b) {
        return b.sellerName() == null || b.sellerName().isBlank() ? "Seller " + b.sellerOrgId() : b.sellerName();
    }

    /** Copies reassigned packages onto a new drop-off of the target route. @return the new stop */
    RouteStop addReassigned(DeliveryRoute target, RouteStop original, List<RoutePackage> moving,
                            Map<String, DeliveryShipment> shipmentsById, Instant now) {
        RouteStop drop = RouteStop.pending(UUID.randomUUID().toString(), StopKind.DROPOFF, 0, original.name(), null,
                original.orderNumber(), original.addressLine(), original.city(), original.postalCode(),
                original.point(), target.window(), original.handoverRequired(), original.safePlaceAllowed(), now);
        List<RoutePackage> pkgs = new ArrayList<>();
        for (RoutePackage p : moving) {
            boolean stillAtSeller = p.status() == PackageStatus.NOT_PICKED_UP
                    || p.status() == PackageStatus.REASSIGNED && p.origin() == PackageOrigin.SELLER;
            String pickupStopId = null;
            PackageOrigin origin = PackageOrigin.FC;
            if (stillAtSeller) {
                DeliveryShipment s = shipmentsById.get(p.shipmentId());
                origin = PackageOrigin.SELLER;
                pickupStopId = pickupStop(target, s, now).id();
            }
            pkgs.add(new RoutePackage(UUID.randomUUID().toString(), p.label(), p.shipmentId(), p.orderNumber(),
                    origin, pickupStopId, drop.id(), p.perishable(), PackageStatus.EXPECTED, false, false, false,
                    now));
        }
        target.addStop(drop, pkgs);
        return drop;
    }

    /** The route's pending pickup at the shipment's seller location, created when missing (one per location). */
    private RouteStop pickupStop(DeliveryRoute route, DeliveryShipment s, Instant now) {
        String sellerOrgId = s == null ? null : s.sellerOrgId();
        String locationId = s == null ? null : s.pickupLocationId();
        Optional<RouteStop> open = route.openPickupFor(sellerOrgId, locationId);
        if (open.isPresent()) {
            return open.get();
        }
        String name = s == null || s.sellerName() == null ? "Seller " + sellerOrgId : s.sellerName();
        RouteStop pickup = RouteStop.pendingPickup(UUID.randomUUID().toString(), name, sellerOrgId, locationId,
                s == null ? null : s.pickupPoint(), route.window(), now);
        route.addPickupStop(pickup);
        return pickup;
    }
}
