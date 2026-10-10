package com.afrotransact.orders.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
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
     * without asking the customer for an address.
     */
    public record OrderPlaced(String orderId, String orderNumber, String customerId, String currency,
                              long itemsMinor, long shippingMinor, long taxMinor, long discountMinor,
                              long totalMinor, Instant placedAt, String shipToCity, String shipToState,
                              String shipToPostalCode, String shipToCountry, List<ShipmentSummary> shipments,
                              String shipToName, String shipToLine1, String shipToLine2, String paymentId) {

        static OrderPlaced of(Order o, Function<Shipment, LocalDate> shipBy) {
            ShipTo to = o.shipTo();
            return new OrderPlaced(o.id(), o.number(), o.customerId(), o.currency(), o.totals().itemsMinor(),
                    o.totals().shippingMinor(), o.totals().taxMinor(), o.totals().discountMinor(),
                    o.totals().grandTotalMinor(), o.placedAt(), to == null ? null : to.city(),
                    to == null ? null : to.state(), to == null ? null : to.postalCode(),
                    to == null ? null : to.country(),
                    o.shipments().stream().map(s -> ShipmentSummary.of(s, to, shipBy.apply(s))).toList(),
                    to == null ? null : to.fullName(), to == null ? null : to.line1(), to == null ? null : to.line2(),
                    o.paymentId());
        }
    }

    /**
     * One shipment of a placed order. {@code nodeId} is the fulfilment node of AfroTransact-fulfilled shipments;
     * the ship-to street line and position let fulfilment route AfroTransact Delivery stops (lat/lng null when
     * the address has no known position). {@code shippingMinor} is the shipment's shipping (charged on its first
     * line); {@code shipBy} the date it must be handed over by (seller health lateness).
     */
    public record ShipmentSummary(String shipmentId, String sellerOrgId, String sellerName, String fulfilmentType,
                                  String deliveryMethod, String serviceLevel, LocalDate promisedBy,
                                  boolean handoverRequired, long totalMinor, List<LineSummary> lines, String nodeId,
                                  boolean perishable, String shipToLine1, String shipToLine2, Double lat,
                                  Double lng, long shippingMinor, LocalDate shipBy, LocalDate promisedFrom) {

        static ShipmentSummary of(Shipment s, ShipTo to, LocalDate shipBy) {
            return new ShipmentSummary(s.id(), s.sellerOrgId(), s.sellerName(), s.fulfilmentType().name(),
                    s.deliveryMethod().name(), s.serviceLevel(), s.promisedBy(), s.handoverRequired(),
                    s.totalMinor(), s.lines().stream().map(LineSummary::of).toList(), s.nodeId(), s.perishable(),
                    to == null ? null : to.line1(), to == null ? null : to.line2(), to == null ? null : to.lat(),
                    to == null ? null : to.lng(), s.shippingMinor(), shipBy, s.promisedFrom());
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
                                    long totalMinor, Instant cancelledAt) {

        static ShipmentCancelled of(Order o, Shipment s, StatusChange c) {
            return new ShipmentCancelled(o.id(), o.number(), o.customerId(), s.id(), s.sellerOrgId(),
                    c.actorKind().name(), c.reason(), s.captured(), s.totalMinor(), c.occurredAt());
        }
    }
}
