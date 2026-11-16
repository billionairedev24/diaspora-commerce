package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import com.afrotransact.orders.port.StaffDirectory;
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
    private final StaffDirectory staff;
    private final Clock clock;

    OrderViews(ShipmentTransitionPolicy policy, HandoverCodeVault vault, ReturnWindowPolicy returnWindows,
               OrdersAccess access, OrdersSettings settings, StaffDirectory staff, Clock clock) {
        this.policy = policy;
        this.vault = vault;
        this.returnWindows = returnWindows;
        this.access = access;
        this.settings = settings;
        this.staff = staff;
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
        Map<String, String> names = staffNames(viewer, history);
        Function<Shipment, OrderView.ShipmentView> shipmentView = s -> shipment(o, s, count, viewer,
                byShipment.getOrDefault(s.id(), List.of()), names);
        boolean staff = viewer.kind() == Viewer.Kind.STAFF;
        return new OrderView(o.id(), o.number(), o.status().name().toLowerCase(Locale.ROOT),
                viewer.kind() == Viewer.Kind.SELLER ? null : o.customerId(), o.placedAt(), o.createdAt(),
                o.currency(), totals.itemsMinor(), totals.shippingMinor(), totals.taxMinor(), totals.discountMinor(),
                totals.grandTotalMinor(), o.shipTo() == null ? null : o.shipTo().summary(), o.shipTo(),
                o.cancelReason(), staff ? o.paymentId() : null, visible.size() != count,
                visible.stream().map(shipmentView).toList(), o.origin().channel().name(), o.origin().tags(),
                viewer.kind() == Viewer.Kind.SELLER ? null : o.origin().attribution());
    }

    /** The names of the staff members behind the history, for staff viewers only; best effort, never fails a view. */
    private Map<String, String> staffNames(Viewer viewer, List<StatusChange> history) {
        if (viewer.kind() != Viewer.Kind.STAFF) {
            return Map.of();
        }
        List<String> ids = history.stream().filter(h -> h.actorKind() == ActorKind.DELIVERY_STAFF
                || h.actorKind() == ActorKind.PLATFORM_ADMIN || h.actorKind() == ActorKind.ADMIN_OVERRIDE
                || h.actorKind() == ActorKind.FULFILMENT_OPS).map(StatusChange::actorId)
                .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            return staff.people(ids).stream().filter(p -> p.name() != null && !p.name().isBlank())
                    .collect(Collectors.toMap(StaffDirectory.StaffPerson::id, StaffDirectory.StaffPerson::name,
                            (a, b) -> a));
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }

    OrderView.ShipmentView shipment(Order order, Shipment s, int count, Viewer viewer, List<StatusChange> history) {
        return shipment(order, s, count, viewer, history, Map.of());
    }

    OrderView.ShipmentView shipment(Order order, Shipment s, int count, Viewer viewer, List<StatusChange> history,
                                    Map<String, String> names) {
        List<String> nextActions = nextActions(order, s, viewer);
        String code = viewer.kind() == Viewer.Kind.CUSTOMER && s.handoverRequired() && s.handoverCodeSealed() != null
                && !s.status().terminal() ? vault.reveal(s.id(), s.handoverCodeSealed()) : null;
        int units = s.lines().stream().mapToInt(OrderLine::quantity).sum();
        String label = "Shipment " + s.seq() + " of " + count + (s.sellerName() == null ? "" : " · " + s.sellerName());
        boolean cancellable = customerMayCancel(order, s);
        Instant cancellableUntil = cancellable ? cancelWindowEnd(order, s) : null;
        if (s.preorderShipment()) {
            label = (s.preorder().run() ? "Delivery run · delivered " : "Pre-order · delivered ")
                    + s.preorder().deliversOn()
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
                s.captured(), cancellable, cancellableUntil, nextActions, overrideActions(s, viewer),
                s.lines().stream().map(OrderViews::line).toList(),
                history.stream().map(h -> new OrderView.TimelineEntry(h.from() == null ? null : h.from().wire(),
                        h.to().wire(), h.actorKind().name(), h.source().wire(), h.reason(), h.occurredAt(), proofOf(h.proof(), viewer.kind() == Viewer.Kind.STAFF),
                        names.get(h.actorId()))).toList(),
                returnable, windowEnds, returnWindows.problemReportOnly(s), links,
                s.localDelivery() == null ? null : new OrderView.LocalDeliveryView(
                        s.localDelivery().shipFromLocationId(), s.localDelivery().distanceMiles(),
                        s.localDelivery().durationMinutes(), s.localDelivery().distanceMethod(),
                        s.localDelivery().customerChargeMinor(), viewer.kind() == Viewer.Kind.CUSTOMER ? null
                        : s.localDelivery().sellerFeeMinor(), s.localDelivery().pricingVersion()),
                s.preorder() == null ? null : new OrderView.PreorderView(s.preorder().windowId(),
                        s.preorder().windowCode(), s.preorder().windowName(), s.preorder().deliversOn(),
                        s.preorder().fulfilment().name(), s.preorder().closesAt(), s.preorder().deliveryMissed(),
                        s.preorder().missedChoice() == null ? null : s.preorder().missedChoice().name(),
                        s.preorder().source().name()),
                s.feeRule() == null ? null : new OrderView.DeliveryFeeRuleView(s.feeRule().method(),
                        s.feeRule().ruleId(), s.feeRule().ruleVersion(), s.feeRule().currency(),
                        s.feeRule().amountMinor(), s.feeRule().source(), s.feeRule().band(),
                        s.feeRule().distanceMiles()),
                s.membershipWaiver() == null || viewer.kind() == Viewer.Kind.SELLER ? null
                        : new OrderView.MembershipWaiverView(
                        s.membershipWaiver().tierCode(), s.membershipWaiver().tierName(),
                        s.membershipWaiver().benefit(), s.membershipWaiver().waivedMinor()));
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
        Set<ShipmentStatus> next = switch (viewer.kind()) {
            case CUSTOMER -> policy.nextFor(s, ActorKind.CUSTOMER);
            case SELLER -> policy.nextFor(s, ActorKind.SELLER);
            case STAFF -> staffNext(order, s);
        };
        return sorted(next);
    }

    /**
     * What this staff member may set now. By mode: AfroTransact-delivered shipments offer the delivery steps (to holders
     * of delivery:manage; everything else there is cancelling), seller-shipped shipments only cancelling (the seller
     * moves them, an override is separate), pickup shipments ready, collected and not collected.
     */
    private Set<ShipmentStatus> staffNext(Order order, Shipment s) {
        Set<ShipmentStatus> out = EnumSet.noneOf(ShipmentStatus.class);
        if (order != null) {
            try {
                out.addAll(policy.nextFor(s, access.transitionActor(order, s).kind()));
            } catch (ForbiddenActionException | NotFoundException ex) {
                // not an order-status role: only the delivery steps below may apply
            }
        }
        if (!access.humanStaff()) {
            return out;
        }
        if (s.fulfilmentMode().staffDelivers()) {
            out.removeIf(to -> DeliverySteps.isStep(s, to));
            if (access.holds(OrdersPermissions.DELIVERY_MANAGE)) {
                out.addAll(policy.nextFor(s, ActorKind.DELIVERY_STAFF));
            }
        } else if (s.sellerResponsible()) {
            out.retainAll(EnumSet.of(ShipmentStatus.CANCELLED));
        }
        return out;
    }

    /** What an override may set (staff holding orders:override-shipment, on a seller-shipped shipment). */
    private List<String> overrideActions(Shipment s, Viewer viewer) {
        if (viewer.kind() != Viewer.Kind.STAFF || !access.humanStaff()
                || !access.holds(OrdersPermissions.OVERRIDE_SHIPMENT)) {
            return List.of();
        }
        return sorted(policy.nextFor(s, ActorKind.ADMIN_OVERRIDE));
    }

    private static List<String> sorted(Set<ShipmentStatus> statuses) {
        return statuses.stream().sorted(Comparator.comparingInt(ShipmentStatus::rank)
                .thenComparingInt(ShipmentStatus::ordinal)).map(ShipmentStatus::wire).toList();
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
                p.signatureAssetId(), staff ? p.latitude() : null, staff ? p.longitude() : null, p.photos());
    }
}
