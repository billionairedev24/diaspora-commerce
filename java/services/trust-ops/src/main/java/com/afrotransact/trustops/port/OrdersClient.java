package com.afrotransact.trustops.port;

import java.util.List;
import java.util.Optional;

/**
 * orders-service {@code GET /v1/lookup/orders/{idOrNumber}} (client scope {@code orders:lookup}): who bought an order,
 * its payment and the line ids of each shipment, so claims check ownership and refund without staff typing ids.
 * Throws {@link UpstreamException} when orders-service is unavailable.
 */
public interface OrdersClient {

    /** Empty when orders-service does not know the order. */
    Optional<OrderFacts> order(String orderIdOrNumber);

    /** An order as claims need it. */
    record OrderFacts(String orderId, String orderNumber, String customerId, String paymentId, String currency,
                      List<ShipmentFacts> shipments) {

        public OrderFacts {
            shipments = shipments == null ? List.of() : List.copyOf(shipments);
        }

        public Optional<ShipmentFacts> shipment(String shipmentId) {
            return shipments.stream().filter(s -> s.shipmentId().equals(shipmentId)).findFirst();
        }

        /** Line ids a claim covers: the shipment's, or every line the seller sold on the order. */
        public List<String> lineIds(String sellerOrgId, String shipmentId) {
            return shipments.stream()
                    .filter(s -> shipmentId != null ? s.shipmentId().equals(shipmentId)
                            : s.sellerOrgId().equals(sellerOrgId))
                    .flatMap(s -> s.lineIds().stream()).toList();
        }
    }

    /** One shipment with its seller and line ids (= payments line ids). */
    record ShipmentFacts(String shipmentId, String sellerOrgId, String status, List<String> lineIds) {

        public ShipmentFacts {
            lineIds = lineIds == null ? List.of() : List.copyOf(lineIds);
        }
    }
}
