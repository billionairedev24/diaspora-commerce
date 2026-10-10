package com.afrotransact.fulfilment.application;

import java.time.LocalDate;
import java.util.List;

/**
 * The fields of {@code orders.order.placed} fulfilment needs (orders.yaml OrdersOrderPlaced). {@code perishable},
 * {@code nodeId} and the ship-to position are read when orders-service sends them (not in the contract yet).
 */
public record PlacedOrder(String orderId, String orderNumber, String customerId, String shipToCity,
                          String shipToState, String shipToPostalCode, String shipToCountry, Double shipToLat,
                          Double shipToLon, List<PlacedShipment> shipments) {

    public PlacedOrder {
        shipments = shipments == null ? List.of() : List.copyOf(shipments);
    }

    /**
     * One shipment of the order ({@code shipBy}: date it must leave its origin by; {@code pickup}: the seller's
     * pickup location for AfroTransact Delivery from the seller's store, S255).
     */
    public record PlacedShipment(String shipmentId, String sellerOrgId, String sellerName, String fulfilmentType,
                                 String deliveryMethod, LocalDate promisedBy, boolean handoverRequired,
                                 Boolean perishable, String nodeId, List<PlacedLine> lines, LocalDate shipBy,
                                 SellerPickup pickup) {

        public PlacedShipment {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        /** A shipment without a pickup location. */
        public PlacedShipment(String shipmentId, String sellerOrgId, String sellerName, String fulfilmentType,
                              String deliveryMethod, LocalDate promisedBy, boolean handoverRequired, Boolean perishable,
                              String nodeId, List<PlacedLine> lines, LocalDate shipBy) {
            this(shipmentId, sellerOrgId, sellerName, fulfilmentType, deliveryMethod, promisedBy, handoverRequired,
                    perishable, nodeId, lines, shipBy, null);
        }

        /** A shipment without ship-by date (orders placed before orders sent it). */
        public PlacedShipment(String shipmentId, String sellerOrgId, String sellerName, String fulfilmentType,
                              String deliveryMethod, LocalDate promisedBy, boolean handoverRequired, Boolean perishable,
                              String nodeId, List<PlacedLine> lines) {
            this(shipmentId, sellerOrgId, sellerName, fulfilmentType, deliveryMethod, promisedBy, handoverRequired,
                    perishable, nodeId, lines, null, null);
        }

        public boolean afrotransactFulfilled() {
            return "FBA_AFROTRANSACT".equals(fulfilmentType) || "RETAIL_1P".equals(fulfilmentType);
        }

        public boolean afrotransactDelivery() {
            return "AFROTRANSACT_DELIVERY".equals(deliveryMethod);
        }
    }

    /** Where an AfroTransact driver collects a seller-packed shipment (orders' checkout snapshot). */
    public record SellerPickup(String locationId, Double lat, Double lng) {
    }

    /** One order line. */
    public record PlacedLine(String lineId, String variantAtin, String title, int quantity) {
    }
}
