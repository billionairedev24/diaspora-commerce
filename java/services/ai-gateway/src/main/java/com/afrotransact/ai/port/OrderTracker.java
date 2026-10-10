package com.afrotransact.ai.port;

import java.util.List;

/** orders-service {@code GET /v1/orders/{id}} under the shopper's token (they only see their own orders). */
public interface OrderTracker {

    Lookup track(String orderId, String bearerToken);

    /** Lookup outcome. */
    enum Availability {
        FOUND,
        NOT_FOUND,
        /** The orders service is not deployed yet or did not answer. */
        UNAVAILABLE
    }

    /**
     * Order status.
     *
     * @param availability whether the order could be looked up
     * @param orderId      order id or number
     * @param status       order status
     * @param shipments    shipment statuses and tracking
     */
    record Lookup(Availability availability, String orderId, String status, List<Shipment> shipments) {

        public Lookup {
            shipments = shipments == null ? List.of() : List.copyOf(shipments);
        }

        public static Lookup of(Availability availability, String orderId) {
            return new Lookup(availability, orderId, "", List.of());
        }
    }

    /** One shipment of an order. */
    record Shipment(String shipmentId, String status, String carrier, String trackingNumber, String promisedBy) {
    }
}
