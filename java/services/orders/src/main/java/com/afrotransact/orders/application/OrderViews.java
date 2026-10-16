package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.afrotransact.orders.domain.ActorKind;
import com.afrotransact.orders.domain.CourierProof;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrderTotals;
import com.afrotransact.orders.domain.ReturnWindowPolicy;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.ShipmentTransitionPolicy;
import com.afrotransact.orders.domain.StatusChange;
import com.afrotransact.orders.port.HandoverCodeVault;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/** Builds {@link OrderView}s for a viewer. */
@Component
class OrderViews {

    private final ShipmentTransitionPolicy policy;
    private final HandoverCodeVault vault;
    private final ReturnWindowPolicy returnWindows;
    private final OrdersAccess access;
    private final OrdersSettings settings;
    private final Clock clock;

    OrderViews(ShipmentTransitionPolicy policy, HandoverCodeVault vault, ReturnWindowPolicy returnWindows,
               OrdersAccess access, OrdersSettings settings, Clock clock) {
        this.policy = policy;
        this.vault = vault;
        this.returnWindows = returnWindows;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    OrderView of(Order o, Viewer viewer, List<StatusChange> history) {
        List<Shipment> visible = o.shipments().stream()
                .filter(s -> viewer.kind() != Viewer.Kind.SELLER || s.sellerOrgId().equals(viewer.orgId()))
                .toList();
        Map<String, List<StatusChange>> byShipment = history.stream()
                .collect(Collectors.groupingBy(StatusChange::shipmentId));
        OrderTotals totals = visible.size() == o.shipments().size() ? o.totals() : OrderTotals.ofShipments(visible);
        int count = o.shipments().size();
        Function<Shipment, OrderView.ShipmentView> shipmentView = s -> shipment(o, s, count, viewer,
                byShipment.getOrDefault(s.id(), List.of()));
        boolean staff = viewer.kind() == Viewer.Kind.STAFF;
        return new OrderView(o.id(), o.number(), o.status().name().toLowerCase(Locale.ROOT),
                viewer.kind() == Viewer.Kind.SELLER ? null : o.customerId(), o.placedAt(), o.createdAt(),
                o.currency(), totals.itemsMinor(), totals.shippingMinor(), totals.taxMinor(), totals.discountMinor(),
                totals.grandTotalMinor(), o.shipTo() == null ? null : o.shipTo().summary(), o.shipTo(),
                o.cancelReason(), staff ? o.paymentId() : null, visible.size() != count,
                visible.stream().map(shipmentView).toList(), o.origin().channel().name(), o.origin().tags(),
                viewer.kind() == Viewer.Kind.SELLER ? null : o.origin().attribution());
    }

    OrderView.ShipmentView shipment(Order order, Shipment s, int count, Viewer viewer, List<StatusChange> history) {
        List<String> nextActions = nextActions(order, s, viewer);
        String code = viewer.kind() == Viewer.Kind.CUSTOMER && s.handoverRequired() && s.handoverCodeSealed() != null
                && !s.status().terminal() ? vault.reveal(s.id(), s.handoverCodeSealed()) : null;
        int units = s.lines().stream().mapToInt(OrderLine::quantity).sum();
        String label = "Shipment " + s.seq() + " of " + count + (s.sellerName() == null ? "" : " · " + s.sellerName());
        boolean cancellable = customerMayCancel(order, s);
        Instant cancellableUntil = cancellable ? cancelWindowEnd(order, s) : null;
        if (s.preorderShipment()) {
            label = "Pre-order · delivered " + s.preorder().deliversOn()
                    + (s.sellerName() == null ? "" : " · " + s.sellerName());
        }
        Instant windowEnds = returnWindows.windowEndsAt(s).orElse(null);
        boolean returnable = windowEnds != null && Instant.now(clock).isBefore(windowEnds);
        OrderView.ReturnLinks links = viewer.kind() == Viewer.Kind.SELLER || s.status() != ShipmentStatus.DELIVERED
                ? null : new OrderView.ReturnLinks("/v1/returns/eligibility?orderId=" + s.orderId(),
                "/v1/returns?orderId=" + s.orderId());
        return new OrderView.ShipmentView(s.id(), s.id(), s.seq(), label, s.sellerOrgId(), s.sellerName(),
                s.fulfilmentType().name(), s.nodeId(), s.deliveryMethod().name(), s.serviceLevel(),
                s.pickupLocationId(), s.preferences(), s.perishable(), s.handoverRequired(), code, s.status().wire(),
                units + (units == 1 ? " item" : " items"), s.itemsMinor(), s.shippingMinor(), s.taxMinor(),
                s.totalMinor(), s.carrier(), s.trackingNumber(), s.promisedFrom(), s.promisedBy(), s.deliveredAt(),
                s.captured(), cancellable, cancellableUntil, nextActions,
                s.lines().stream().map(OrderViews::line).toList(),
                history.stream().map(h -> new OrderView.TimelineEntry(h.from() == null ? null : h.from().wire(),
                        h.to().wire(), h.actorKind().name(), h.source().wire(), h.reason(), h.occurredAt(), proofOf(h.proof(), viewer.kind() == Viewer.Kind.STAFF))).toList(),
                returnable, windowEnds, returnWindows.problemReportOnly(s), links,
                s.localDelivery() == null ? null : new OrderView.LocalDeliveryView(
                        s.localDelivery().shipFromLocationId(), s.localDelivery().distanceMiles(),
                        s.localDelivery().durationMinutes(), s.localDelivery().distanceMethod(),
                        s.localDelivery().customerChargeMinor(), viewer.kind() == Viewer.Kind.CUSTOMER ? null
                        : s.localDelivery().sellerFeeMinor(), s.localDelivery().pricingVersion()),
                s.preorder() == null ? null : new OrderView.PreorderView(s.preorder().windowId(),
                        s.preorder().windowCode(), s.preorder().windowName(), s.preorder().deliversOn(),
                        s.preorder().fulfilment().name(), s.preorder().closesAt(), s.preorder().deliveryMissed(),
                        s.preorder().missedChoice() == null ? null : s.preorder().missedChoice().name()));
    }

    /**
     * The customer's Cancel: only before the parcel is packed (placed, or confirmed by the seller) and inside the
     * cancellation window (a pre-order: until its window closes). The same two rules the cancel itself enforces, so
     * the button is never shown for a cancel that would be refused.
     */
    private boolean customerMayCancel(Order order, Shipment s) {
        if (s.status() != ShipmentStatus.PLACED && s.status() != ShipmentStatus.CONFIRMED) {
            return false;
        }
        Instant now = Instant.now(clock);
        if (s.preorderShipment()) {
            return s.preorder().cancellableAt(now);
        }
        Instant until = cancelWindowEnd(order, s);
        return until != null && !now.isAfter(until);
    }

    /** When that window ends: placing plus the cancellation window, or the pre-order's close (null: not known). */
    private Instant cancelWindowEnd(Order order, Shipment s) {
        if (s.preorderShipment()) {
            return s.preorder().closesAt();
        }
        return order == null || order.placedAt() == null ? null
                : order.placedAt().plus(settings.cancellationWindow());
    }

    private List<String> nextActions(Order order, Shipment s, Viewer viewer) {
        ActorKind actor = switch (viewer.kind()) {
            case CUSTOMER -> ActorKind.CUSTOMER;
            case SELLER -> ActorKind.SELLER;
            case STAFF -> {
                if (order == null) {
                    yield null;
                }
                try {
                    yield access.transitionActor(order, s).kind();
                } catch (ForbiddenActionException | NotFoundException ex) {
                    yield null;
                }
            }
        };
        if (actor == null) {
            return List.of();
        }
        return policy.nextFor(s, actor).stream().sorted(Comparator.comparingInt(ShipmentStatus::rank))
                .map(ShipmentStatus::wire).toList();
    }

    private static OrderView.LineView line(OrderLine l) {
        return new OrderView.LineView(l.id(), l.lineNo(), l.variantAtin(), l.productAtin(), l.title(), l.imageUrl(),
                l.quantity(), l.unitPriceMinor(), l.itemsMinor(), l.taxMinor(), l.discountMinor(), l.category(),
                l.perishable(), l.taxJurisdiction(), l.productType());
    }

    /** Staff see everything the driver recorded; the customer and the seller do not get the driver's GPS position. */
    private static OrderView.DeliveryProof proofOf(CourierProof p, boolean staff) {
        if (p == null) {
            return null;
        }
        return new OrderView.DeliveryProof(p.handoverMethod() == null ? null : p.handoverMethod().name(),
                p.recipientName(), p.relationship(), p.safePlace(), p.failureReason(), p.photoAssetId(),
                p.signatureAssetId(), staff ? p.latitude() : null, staff ? p.longitude() : null);
    }
}
