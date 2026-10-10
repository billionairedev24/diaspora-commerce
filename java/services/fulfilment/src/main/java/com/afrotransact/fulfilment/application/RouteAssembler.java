package com.afrotransact.fulfilment.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.DeliveryShipment;
import com.afrotransact.fulfilment.domain.PackageOrigin;
import com.afrotransact.fulfilment.domain.PackageStatus;
import com.afrotransact.fulfilment.domain.RoutePackage;
import com.afrotransact.fulfilment.domain.RouteStop;
import com.afrotransact.fulfilment.domain.StopKind;
import com.afrotransact.fulfilment.port.IdIssuer;
import org.springframework.stereotype.Component;

/**
 * Turns dispatch-pool shipments into stops and packages: one drop-off per order (same address), one pickup per
 * seller for seller-origin packages (visited before the drop-offs), one package per shipment with its ATS label.
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
                    pickupStopId = pickupStop(route, s.sellerOrgId(), s.sellerName(), now).id();
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
                pickupStopId = pickupStop(target, s == null ? null : s.sellerOrgId(), s == null ? null
                        : s.sellerName(), now).id();
            }
            pkgs.add(new RoutePackage(UUID.randomUUID().toString(), p.label(), p.shipmentId(), p.orderNumber(),
                    origin, pickupStopId, drop.id(), p.perishable(), PackageStatus.EXPECTED, false, false, false,
                    now));
        }
        target.addStop(drop, pkgs);
        return drop;
    }

    private RouteStop pickupStop(DeliveryRoute route, String sellerOrgId, String sellerName, Instant now) {
        Optional<RouteStop> open = route.openPickupFor(sellerOrgId);
        if (open.isPresent()) {
            return open.get();
        }
        RouteStop pickup = RouteStop.pending(UUID.randomUUID().toString(), StopKind.PICKUP, 0,
                sellerName == null ? "Seller " + sellerOrgId : sellerName, sellerOrgId, null, null, null, null, null,
                route.window(), false, false, now);
        route.addPickupStop(pickup);
        return pickup;
    }
}
