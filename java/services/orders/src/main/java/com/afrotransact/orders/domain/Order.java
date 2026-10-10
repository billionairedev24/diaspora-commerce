package com.afrotransact.orders.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A customer order: one checkout, one payment, one shipment per seller and fulfilment node.
 *
 * @param id             order id (also the payments / inventory order id and Stripe transfer group)
 * @param number         human order number, e.g. AT-2026-7KQ3MD
 * @param customerId     identity subject of the shopper
 * @param currency       ISO 4217 code
 * @param status         order status
 * @param totals         items, shipping, tax, discount, grand total (minor units)
 * @param shipTo         delivery address snapshot
 * @param paymentId      payments-service payment id
 * @param workflowId     checkout saga workflow id
 * @param cancelReason   why the checkout was voided / the order cancelled
 * @param placedAt       when the payment was authorised and the order placed
 * @param createdAt      when the checkout created the order
 * @param shipments      sub-orders
 * @param version        optimistic lock version
 * @param origin         attribution, channel, tags and on-behalf details (S272)
 */
public record Order(String id, String number, String customerId, String currency, OrderStatus status,
                    OrderTotals totals, ShipTo shipTo, String paymentId, String workflowId, String cancelReason,
                    Instant placedAt, Instant createdAt, List<Shipment> shipments, long version, OrderOrigin origin) {

    /** An order of the shopper's own checkout without attribution. */
    public Order(String id, String number, String customerId, String currency, OrderStatus status,
                 OrderTotals totals, ShipTo shipTo, String paymentId, String workflowId, String cancelReason,
                 Instant placedAt, Instant createdAt, List<Shipment> shipments, long version) {
        this(id, number, customerId, currency, status, totals, shipTo, paymentId, workflowId, cancelReason, placedAt,
                createdAt, shipments, version, OrderOrigin.none());
    }

    public Order {
        origin = origin == null ? OrderOrigin.none() : origin;
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(totals, "totals");
        shipments = List.copyOf(shipments);
        if (shipments.isEmpty()) {
            throw new OrdersRuleViolation("orders.empty-order", "An order needs at least one shipment");
        }
        if (!totals.equals(OrderTotals.ofShipments(shipments))) {
            throw new IllegalArgumentException("order totals must equal the sum of its shipments");
        }
    }

    public List<OrderLine> lines() {
        return shipments.stream().flatMap(s -> s.lines().stream()).toList();
    }

    public Shipment shipment(String shipmentId) {
        return shipments.stream().filter(s -> s.id().equals(shipmentId)).findFirst()
                .orElseThrow(() -> new OrdersRuleViolation("orders.shipment-not-in-order",
                        "Shipment " + shipmentId + " is not part of order " + number));
    }

    /** Copy with a new header status, cancellation reason and placement time. */
    public Order withHeader(OrderStatus newStatus, String newCancelReason, Instant newPlacedAt) {
        return new Order(id, number, customerId, currency, newStatus, totals, shipTo, paymentId, workflowId,
                newCancelReason, newPlacedAt, createdAt, shipments, version, origin);
    }

    /** Replaces one shipment and re-derives the order status (placed orders only). */
    public Order withShipment(Shipment updated) {
        List<Shipment> next = shipments.stream().map(s -> s.id().equals(updated.id()) ? updated : s).toList();
        OrderStatus derived = OrderStatus.derive(next.stream().map(Shipment::status).toList());
        return new Order(id, number, customerId, currency, derived, totals, shipTo, paymentId, workflowId,
                cancelReason, placedAt, createdAt, next, version, origin);
    }

    /** Replaces one shipment's data without re-deriving the status (e.g. a pre-order snapshot change). */
    public Order withShipmentData(Shipment updated) {
        List<Shipment> next = shipments.stream().map(s -> s.id().equals(updated.id()) ? updated : s).toList();
        return new Order(id, number, customerId, currency, status, totals, shipTo, paymentId, workflowId,
                cancelReason, placedAt, createdAt, next, version, origin);
    }

    /** The PREORDER shipments (S272). */
    public List<Shipment> preorderShipments() {
        return shipments.stream().filter(Shipment::preorderShipment).toList();
    }
}
