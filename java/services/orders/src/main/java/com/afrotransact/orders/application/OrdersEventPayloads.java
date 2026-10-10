package com.afrotransact.orders.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.StatusChange;

/** Payloads of the orders events (JSON on the wire). */
public final class OrdersEventPayloads {

    private OrdersEventPayloads() {
    }

    /** orders.order.placed — notifications (order confirmation), indexer stats, tax-returns. */
    public record OrderPlaced(String orderId, String orderNumber, String customerId, String currency,
                              long itemsMinor, long shippingMinor, long taxMinor, long discountMinor,
                              long totalMinor, Instant placedAt, String shipToCity, String shipToState,
                              String shipToPostalCode, String shipToCountry, List<ShipmentSummary> shipments) {

        static OrderPlaced of(Order o) {
            return new OrderPlaced(o.id(), o.number(), o.customerId(), o.currency(), o.totals().itemsMinor(),
                    o.totals().shippingMinor(), o.totals().taxMinor(), o.totals().discountMinor(),
                    o.totals().grandTotalMinor(), o.placedAt(), o.shipTo() == null ? null : o.shipTo().city(),
                    o.shipTo() == null ? null : o.shipTo().state(), o.shipTo() == null ? null : o.shipTo().postalCode(),
                    o.shipTo() == null ? null : o.shipTo().country(),
                    o.shipments().stream().map(s -> ShipmentSummary.of(s, o.shipTo())).toList());
        }
    }

    /**
     * One shipment of a placed order. {@code nodeId} is the fulfilment node of AfroTransact-fulfilled shipments;
     * the ship-to street line and position let fulfilment route AfroTransact Delivery stops (lat/lng null when
     * the address has no known position).
     */
    public record ShipmentSummary(String shipmentId, String sellerOrgId, String sellerName, String fulfilmentType,
                                  String deliveryMethod, String serviceLevel, LocalDate promisedBy,
                                  boolean handoverRequired, long totalMinor, List<LineSummary> lines, String nodeId,
                                  boolean perishable, String shipToLine1, String shipToLine2, Double lat,
                                  Double lng) {

        static ShipmentSummary of(Shipment s, ShipTo to) {
            return new ShipmentSummary(s.id(), s.sellerOrgId(), s.sellerName(), s.fulfilmentType().name(),
                    s.deliveryMethod().name(), s.serviceLevel(), s.promisedBy(), s.handoverRequired(),
                    s.totalMinor(), s.lines().stream().map(LineSummary::of).toList(), s.nodeId(), s.perishable(),
                    to == null ? null : to.line1(), to == null ? null : to.line2(), to == null ? null : to.lat(),
                    to == null ? null : to.lng());
        }
    }

    /** One line. */
    public record LineSummary(String lineId, String variantAtin, String title, int quantity, long unitPriceMinor,
                              long taxMinor) {

        static LineSummary of(OrderLine l) {
            return new LineSummary(l.id(), l.variantAtin(), l.title(), l.quantity(), l.unitPriceMinor(),
                    l.taxMinor());
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
