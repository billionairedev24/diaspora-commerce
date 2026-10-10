package com.afrotransact.taxreturns.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The fields of orders-service's {@code orders.order.placed} event this service keeps. Orders now sends the ship-to
 * street and name, the shipment's shipping and per line the product type, perishable flag, discount and tax
 * jurisdictions; older events lack them (null), and the snapshot then falls back to catalog facts and derived
 * shipping.
 */
public record OrderPlaced(UUID orderId, String orderNumber, String customerId, String currency, Instant placedAt,
                          String shipToCity, String shipToState, String shipToPostalCode, String shipToCountry,
                          List<Shipment> shipments, String shipToName, String shipToLine1, String shipToLine2,
                          String paymentId) {

    public OrderPlaced {
        shipments = shipments == null ? List.of() : List.copyOf(shipments);
    }

    /** An event without street, name or payment (older orders-service). */
    public OrderPlaced(UUID orderId, String orderNumber, String customerId, String currency, Instant placedAt,
                       String shipToCity, String shipToState, String shipToPostalCode, String shipToCountry,
                       List<Shipment> shipments) {
        this(orderId, orderNumber, customerId, currency, placedAt, shipToCity, shipToState, shipToPostalCode,
                shipToCountry, shipments, null, null, null, null);
    }

    /** @param shippingMinor the shipment's shipping as charged; null in older events (derived then) */
    public record Shipment(UUID shipmentId, String sellerOrgId, String sellerName, String fulfilmentType,
                           String deliveryMethod, long totalMinor, List<Line> lines, Long shippingMinor) {

        public Shipment {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        public Shipment(UUID shipmentId, String sellerOrgId, String sellerName, String fulfilmentType,
                        String deliveryMethod, long totalMinor, List<Line> lines) {
            this(shipmentId, sellerOrgId, sellerName, fulfilmentType, deliveryMethod, totalMinor, lines, null);
        }
    }

    /**
     * One line.
     *
     * @param productType          catalog product type (null in older events: catalog is asked)
     * @param perishable           null in older events
     * @param discountMinor        coupon discount of the line
     * @param shippingTaxMinor     part of taxMinor charged on shipping
     */
    public record Line(String lineId, String variantAtin, String title, int quantity, long unitPriceMinor,
                       long taxMinor, String productType, Boolean perishable, long discountMinor,
                       String taxJurisdiction, String localTaxJurisdiction, long shippingTaxMinor) {

        public Line(String lineId, String variantAtin, String title, int quantity, long unitPriceMinor,
                    long taxMinor) {
            this(lineId, variantAtin, title, quantity, unitPriceMinor, taxMinor, null, null, 0, null, null, 0);
        }
    }
}
