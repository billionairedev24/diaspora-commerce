package com.afrotransact.orders.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

import com.afrotransact.orders.domain.Attribution;
import com.afrotransact.orders.domain.LocalDelivery;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrderOrigin;
import com.afrotransact.orders.domain.PreorderPackage;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.StatusChange;

/** Payloads of the orders events (JSON on the wire). */
public final class OrdersEventPayloads {

    private OrdersEventPayloads() {
    }

    /**
     * orders.order.placed — notifications (order confirmation), indexer stats, tax-returns (returns, tax ledger),
     * fulfilment (dispatch), trust-ops (seller health). The ship-to street lines and name let return labels be bought
     * without asking the customer for an address. S272: {@code attribution} {src, cr, po} and {@code channel} as
     * stored on the order, {@code tags} (LATE_ADD), {@code onBehalf} for orders created by customer service, and per
     * shipment its {@code preorder} window and fulfilment.
     */
    public record OrderPlaced(String orderId, String orderNumber, String customerId, String currency,
                              long itemsMinor, long shippingMinor, long taxMinor, long discountMinor,
                              long totalMinor, Instant placedAt, String shipToCity, String shipToState,
                              String shipToPostalCode, String shipToCountry, List<ShipmentSummary> shipments,
                              String shipToName, String shipToLine1, String shipToLine2, String paymentId,
                              Attribution attribution, String channel, List<String> tags, OnBehalf onBehalf,
                              String shipToPhone) {

        static OrderPlaced of(Order o, Function<Shipment, LocalDate> shipBy) {
            ShipTo to = o.shipTo();
            return new OrderPlaced(o.id(), o.number(), o.customerId(), o.currency(), o.totals().itemsMinor(),
                    o.totals().shippingMinor(), o.totals().taxMinor(), o.totals().discountMinor(),
                    o.totals().grandTotalMinor(), o.placedAt(), to == null ? null : to.city(),
                    to == null ? null : to.state(), to == null ? null : to.postalCode(),
                    to == null ? null : to.country(),
                    o.shipments().stream().map(s -> ShipmentSummary.of(o, s, to, shipBy.apply(s))).toList(),
                    to == null ? null : to.fullName(), to == null ? null : to.line1(), to == null ? null : to.line2(),
                    o.paymentId(), o.origin().attribution(), o.origin().channel().name(), o.origin().tags(),
                    OnBehalf.of(o.origin()), to == null ? null : to.phone());
        }
    }

    /**
     * An order customer service created on the customer's behalf (S272 late pre-order add).
     *
     * @param actorId staff subject
     * @param reason  Customer called, Chat, Driver report, Other
     */
    public record OnBehalf(String actorId, String reason, String note) {

        static OnBehalf of(OrderOrigin origin) {
            return origin.actorId() == null ? null : new OnBehalf(origin.actorId(), origin.reason(), origin.note());
        }
    }

    /**
     * The pre-order block of a PREORDER shipment (S272; asyncapi OrdersPreorderBlock).
     *
     * @param code       window code, e.g. LBK-7
     * @param number     pre-order number shown to the customer (assigned by delivery-runs; null here)
     * @param closesAt   the customer may cancel for a full refund until then
     * @param fulfilment DOOR | PICKUP
     * @param feeMinor   the window's door or pickup fee charged on the order (all its shipments of that window)
     */
    public record PreorderSummary(String windowId, String code, String name, String number, LocalDate deliversOn,
                                  Instant closesAt, String fulfilment, long feeMinor, String currency,
                                  String pickupWindow) {

        static PreorderSummary of(Order o, PreorderPackage p) {
            if (p == null) {
                return null;
            }
            long fee = o.preorderShipments().stream().filter(s -> s.preorder().windowId().equals(p.windowId()))
                    .mapToLong(Shipment::shippingMinor).sum();
            return new PreorderSummary(p.windowId(), p.windowCode(), p.windowName(), null, p.deliversOn(),
                    p.closesAt(), p.fulfilment().name(), fee, o.currency(), null);
        }
    }

    /**
     * orders.preorder.delivery_missed (S272; asyncapi OrdersPreorderDeliveryMissed): notifications sends
     * {@code preorder.delivery_missed} with a refund and a new-date link; the customer answers with
     * {@code POST /v1/orders/{id}/shipments/{shipmentId}/missed-delivery}. {@code newDate} is set by delivery-runs
     * when it reschedules (null here); {@code totalMinor} is what a refund gives back (delivery fee included).
     */
    public record PreorderDeliveryMissed(String orderId, String orderNumber, String customerId, String shipmentId,
                                         String sellerOrgId, String windowId, String code, String name,
                                         LocalDate deliversOn, LocalDate newDate, boolean offerRefund,
                                         boolean offerNewDate, String refundUrl, String newDateUrl, long totalMinor,
                                         String currency, String reason, PreorderSummary preorder,
                                         List<String> choices, Instant missedAt) {

        static PreorderDeliveryMissed of(Order o, Shipment s, Instant at) {
            PreorderPackage p = s.preorder();
            return new PreorderDeliveryMissed(o.id(), o.number(), o.customerId(), s.id(), s.sellerOrgId(),
                    p.windowId(), p.windowCode(), p.windowName(), p.deliversOn(), null, true, true, null, null,
                    s.totalMinor(), o.currency(), "Not delivered on the pre-order date", PreorderSummary.of(o, p),
                    List.of("REFUND", "WAIT"), at);
        }
    }

    /** orders.preorder.missed_delivery_answered (S272): delivery-runs reschedules WAIT answers. */
    public record PreorderMissedDeliveryAnswered(String orderId, String orderNumber, String customerId,
                                                 String shipmentId, String windowId, String choice,
                                                 Instant answeredAt) {
    }

    /** orders.preorder.payment_link_created (S272): notifications texts the link (preorder.late_order.payment_link). */
    public record PreorderPaymentLinkCreated(String orderId, String orderNumber, String customerId, String windowId,
                                             String windowCode, String url, Instant expiresAt, long amountMinor,
                                             String currency, String shipToName, String shipToPhone) {
    }

    /**
     * One shipment of a placed order. {@code nodeId} is the fulfilment node of AfroTransact-fulfilled shipments;
     * the ship-to street line and position let fulfilment route AfroTransact Delivery stops (lat/lng null when
     * the address has no known position). {@code shippingMinor} is the shipment's shipping (charged on its first
     * line); {@code shipBy} the date it must be handed over by (seller health lateness). {@code pickup} is set for
     * AfroTransact Delivery from the seller's store (S255): where the driver collects and the checkout snapshot.
     * {@code preorder} is set for community pre-order shipments (S272): window id, code, delivery date, close and
     * fulfilment (DOOR | PICKUP).
     */
    public record ShipmentSummary(String shipmentId, String sellerOrgId, String sellerName, String fulfilmentType,
                                  String deliveryMethod, String serviceLevel, LocalDate promisedBy,
                                  boolean handoverRequired, long totalMinor, List<LineSummary> lines, String nodeId,
                                  boolean perishable, String shipToLine1, String shipToLine2, Double lat,
                                  Double lng, long shippingMinor, LocalDate shipBy, LocalDate promisedFrom,
                                  SellerPickup pickup, PreorderSummary preorder, String windowId,
                                  String fulfilment) {

        static ShipmentSummary of(Order o, Shipment s, ShipTo to, LocalDate shipBy) {
            return new ShipmentSummary(s.id(), s.sellerOrgId(), s.sellerName(), s.fulfilmentType().name(),
                    s.deliveryMethod().name(), s.serviceLevel(), s.promisedBy(), s.handoverRequired(),
                    s.totalMinor(), s.lines().stream().map(LineSummary::of).toList(), s.nodeId(), s.perishable(),
                    to == null ? null : to.line1(), to == null ? null : to.line2(), to == null ? null : to.lat(),
                    to == null ? null : to.lng(), s.shippingMinor(), shipBy, s.promisedFrom(),
                    SellerPickup.of(s.localDelivery()), PreorderSummary.of(o, s.preorder()),
                    s.preorder() == null ? null : s.preorder().windowId(),
                    s.preorder() == null ? null : s.preorder().fulfilment().name());
        }
    }

    /**
     * AfroTransact Delivery pickup at the seller's store (S255).
     *
     * @param roadMiles      road miles pickup → customer (geo routing provider)
     * @param sellerFeeMinor seller's pickup-delivery fee snapshotted at checkout
     */
    public record SellerPickup(String locationId, double lat, double lng, double roadMiles, double straightLineMiles,
                               double durationMinutes, String distanceMethod, long customerChargeMinor,
                               long sellerFeeMinor, int pricingVersion) {

        static SellerPickup of(LocalDelivery d) {
            return d == null ? null : new SellerPickup(d.shipFromLocationId(), d.pickupLat(), d.pickupLng(),
                    d.distanceMiles(), d.straightLineMiles(), d.durationMinutes(), d.distanceMethod(),
                    d.customerChargeMinor(), d.sellerFeeMinor(), d.pricingVersion());
        }
    }

    /**
     * One line.
     *
     * @param productType          catalog product type (tax category, return window, perishables)
     * @param category             payments commission category
     * @param discountMinor        coupon discount allocated to the line
     * @param taxJurisdiction      state jurisdiction of the tax (US-TX)
     * @param localTaxJurisdiction local jurisdiction from the tax quote (US-TX-770), null when none
     * @param shippingTaxMinor     part of taxMinor charged on the shipment's shipping
     */
    public record LineSummary(String lineId, String variantAtin, String title, int quantity, long unitPriceMinor,
                              long taxMinor, String productAtin, String productType, String category,
                              boolean perishable, long discountMinor, String taxJurisdiction,
                              String localTaxJurisdiction, long shippingTaxMinor, int lineNo) {

        static LineSummary of(OrderLine l) {
            return new LineSummary(l.id(), l.variantAtin(), l.title(), l.quantity(), l.unitPriceMinor(),
                    l.taxMinor(), l.productAtin(), l.productType(), l.category(), l.perishable(), l.discountMinor(),
                    l.taxJurisdiction(), l.localTaxJurisdiction(), l.shippingTaxMinor(), l.lineNo());
        }
    }

    /** orders.order.cancelled — checkout voided or every shipment cancelled. */
    public record OrderCancelled(String orderId, String orderNumber, String customerId, String code, String reason,
                                 boolean wasPlaced, Instant cancelledAt) {
    }

    /** orders.shipment.status_changed — every applied transition. */
    public record ShipmentStatusChanged(String orderId, String orderNumber, String customerId, String shipmentId,
                                        String sellerOrgId, String fulfilmentType, String deliveryMethod,
                                        String from, String to, String actorType, String actorId, String source,
                                        String reason, Instant occurredAt, String carrier, String trackingNumber,
                                        LocalDate promisedBy) {

        static ShipmentStatusChanged of(Order o, Shipment s, StatusChange c) {
            return new ShipmentStatusChanged(o.id(), o.number(), o.customerId(), s.id(), s.sellerOrgId(),
                    s.fulfilmentType().name(), s.deliveryMethod().name(), c.from().wire(), c.to().wire(),
                    c.actorKind().name(), c.actorId(), c.source().wire(), c.reason(), c.occurredAt(), s.carrier(),
                    s.trackingNumber(), s.promisedBy());
        }
    }

    /** orders.shipment.delivered — payments settlement, review requests, returns window start. */
    public record ShipmentDelivered(String orderId, String orderNumber, String customerId, String shipmentId,
                                    String sellerOrgId, Instant deliveredAt, long totalMinor, List<String> lineIds,
                                    String source) {

        static ShipmentDelivered of(Order o, Shipment s, StatusChange c) {
            return new ShipmentDelivered(o.id(), o.number(), o.customerId(), s.id(), s.sellerOrgId(),
                    s.deliveredAt(), s.totalMinor(), s.lines().stream().map(OrderLine::id).toList(),
                    c.source().wire());
        }
    }

    /** orders.shipment.cancelled — refunds / releases, seller and customer notifications. */
    public record ShipmentCancelled(String orderId, String orderNumber, String customerId, String shipmentId,
                                    String sellerOrgId, String actorType, String reason, boolean captured,
                                    long totalMinor, Instant cancelledAt, String fulfilmentType, String windowId) {

        static ShipmentCancelled of(Order o, Shipment s, StatusChange c) {
            return new ShipmentCancelled(o.id(), o.number(), o.customerId(), s.id(), s.sellerOrgId(),
                    c.actorKind().name(), c.reason(), s.captured(), s.totalMinor(), c.occurredAt(),
                    s.fulfilmentType().name(), s.preorder() == null ? null : s.preorder().windowId());
        }
    }
}
