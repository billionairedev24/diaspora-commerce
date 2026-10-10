package com.afrotransact.orders.application;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.afrotransact.orders.domain.ActorKind;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrderTotals;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.ShipmentTransitionPolicy;
import com.afrotransact.orders.domain.StatusChange;
import com.afrotransact.orders.port.HandoverCodeVault;
import org.springframework.stereotype.Component;

/** Builds {@link OrderView}s for a viewer. */
@Component
class OrderViews {

    private final ShipmentTransitionPolicy policy;
    private final HandoverCodeVault vault;

    OrderViews(ShipmentTransitionPolicy policy, HandoverCodeVault vault) {
        this.policy = policy;
        this.vault = vault;
    }

    OrderView of(Order o, Viewer viewer, List<StatusChange> history) {
        List<Shipment> visible = o.shipments().stream()
                .filter(s -> viewer.kind() != Viewer.Kind.SELLER || s.sellerOrgId().equals(viewer.orgId()))
                .toList();
        Map<String, List<StatusChange>> byShipment = history.stream()
                .collect(Collectors.groupingBy(StatusChange::shipmentId));
        OrderTotals totals = visible.size() == o.shipments().size() ? o.totals() : OrderTotals.ofShipments(visible);
        int count = o.shipments().size();
        Function<Shipment, OrderView.ShipmentView> shipmentView = s -> shipment(s, count, viewer,
                byShipment.getOrDefault(s.id(), List.of()));
        boolean staff = viewer.kind() == Viewer.Kind.STAFF;
        return new OrderView(o.id(), o.number(), o.status().name().toLowerCase(Locale.ROOT),
                viewer.kind() == Viewer.Kind.SELLER ? null : o.customerId(), o.placedAt(), o.createdAt(),
                o.currency(), totals.itemsMinor(), totals.shippingMinor(), totals.taxMinor(), totals.discountMinor(),
                totals.grandTotalMinor(), o.shipTo() == null ? null : o.shipTo().summary(), o.shipTo(),
                o.cancelReason(), staff ? o.paymentId() : null, visible.size() != count,
                visible.stream().map(shipmentView).toList());
    }

    OrderView.ShipmentView shipment(Shipment s, int count, Viewer viewer, List<StatusChange> history) {
        ActorKind as = switch (viewer.kind()) {
            case CUSTOMER -> ActorKind.CUSTOMER;
            case SELLER -> ActorKind.SELLER;
            case STAFF -> ActorKind.PLATFORM_ADMIN;
        };
        String code = viewer.kind() == Viewer.Kind.CUSTOMER && s.handoverRequired() && s.handoverCodeSealed() != null
                && !s.status().terminal() ? vault.reveal(s.id(), s.handoverCodeSealed()) : null;
        int units = s.lines().stream().mapToInt(OrderLine::quantity).sum();
        String label = "Shipment " + s.seq() + " of " + count + (s.sellerName() == null ? "" : " · " + s.sellerName());
        boolean cancellable = s.status() == ShipmentStatus.PLACED || s.status() == ShipmentStatus.CONFIRMED;
        return new OrderView.ShipmentView(s.id(), s.id(), s.seq(), label, s.sellerOrgId(), s.sellerName(),
                s.fulfilmentType().name(), s.nodeId(), s.deliveryMethod().name(), s.serviceLevel(),
                s.pickupLocationId(), s.preferences(), s.perishable(), s.handoverRequired(), code, s.status().wire(),
                units + (units == 1 ? " item" : " items"), s.itemsMinor(), s.shippingMinor(), s.taxMinor(),
                s.totalMinor(), s.carrier(), s.trackingNumber(), s.promisedFrom(), s.promisedBy(), s.deliveredAt(),
                s.captured(), cancellable,
                policy.nextFor(s, as).stream().sorted(Comparator.comparingInt(ShipmentStatus::rank))
                        .map(ShipmentStatus::wire).toList(),
                s.lines().stream().map(OrderViews::line).toList(),
                history.stream().map(h -> new OrderView.TimelineEntry(h.from() == null ? null : h.from().wire(),
                        h.to().wire(), h.actorKind().name(), h.source().wire(), h.reason(), h.occurredAt())).toList());
    }

    private static OrderView.LineView line(OrderLine l) {
        return new OrderView.LineView(l.id(), l.lineNo(), l.variantAtin(), l.productAtin(), l.title(), l.imageUrl(),
                l.quantity(), l.unitPriceMinor(), l.itemsMinor(), l.taxMinor(), l.discountMinor(), l.category(),
                l.perishable(), l.taxJurisdiction());
    }
}
