package com.afrotransact.fulfilment.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A shipment delivered by AfroTransact Delivery, known from orders.order.placed, waiting for (or on) a route.
 *
 * @param shipmentId       orders-service shipment id
 * @param orderId          order id
 * @param orderNumber      order number (AT-2026-…)
 * @param customerId       customer subject
 * @param sellerOrgId      seller organisation
 * @param sellerName       seller display name
 * @param fulfilmentType   SELLER_FULFILLED, FBA_AFROTRANSACT or RETAIL_1P
 * @param origin           FC (AfroTransact-fulfilled) or SELLER (pickup at the seller)
 * @param nodeId           fulfilment centre for FC shipments
 * @param label            package label (ATS…)
 * @param city             delivery city
 * @param state            delivery state
 * @param postalCode       delivery ZIP
 * @param country          delivery country
 * @param location         geocoded drop-off, null when unknown
 * @param perishable       contains perishables (no safe place, cold box)
 * @param handoverRequired the customer must give their 4-digit code
 * @param units            units in the package
 * @param status           dispatch status
 * @param routeId          route currently carrying it
 * @param promisedBy       promised delivery date
 * @param createdAt        when it was received
 * @param shipBy           date the shipment must leave its origin by (from orders; null for older orders)
 */
public record DeliveryShipment(String shipmentId, String orderId, String orderNumber, String customerId,
                               String sellerOrgId, String sellerName, String fulfilmentType, PackageOrigin origin,
                               String nodeId, String label, String city, String state, String postalCode,
                               String country, GeoPoint location, boolean perishable, boolean handoverRequired,
                               int units, DeliveryShipmentStatus status, String routeId, LocalDate promisedBy,
                               Instant createdAt, LocalDate shipBy) {

    /** A shipment without ship-by date. */
    public DeliveryShipment(String shipmentId, String orderId, String orderNumber, String customerId,
                            String sellerOrgId, String sellerName, String fulfilmentType, PackageOrigin origin,
                            String nodeId, String label, String city, String state, String postalCode,
                            String country, GeoPoint location, boolean perishable, boolean handoverRequired,
                            int units, DeliveryShipmentStatus status, String routeId, LocalDate promisedBy,
                            Instant createdAt) {
        this(shipmentId, orderId, orderNumber, customerId, sellerOrgId, sellerName, fulfilmentType, origin, nodeId,
                label, city, state, postalCode, country, location, perishable, handoverRequired, units, status,
                routeId, promisedBy, createdAt, null);
    }
}
