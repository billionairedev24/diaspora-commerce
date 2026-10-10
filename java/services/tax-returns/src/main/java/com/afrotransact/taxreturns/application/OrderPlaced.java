package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The fields of orders-service's {@code orders.order.placed} event this service keeps. */
public record OrderPlaced(UUID orderId, String orderNumber, String customerId, String currency, Instant placedAt,
                          String shipToCity, String shipToState, String shipToPostalCode, String shipToCountry,
                          List<Shipment> shipments) {

    public OrderPlaced {
        shipments = shipments == null ? List.of() : List.copyOf(shipments);
    }

    public record Shipment(UUID shipmentId, String sellerOrgId, String sellerName, String fulfilmentType,
                           String deliveryMethod, long totalMinor, List<Line> lines) {

        public Shipment {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public record Line(String lineId, String variantAtin, String title, int quantity, long unitPriceMinor,
                       long taxMinor) {
    }
}
