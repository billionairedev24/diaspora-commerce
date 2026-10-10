package com.afrotransact.fulfilment.domain;

/**
 * A shipment waiting for a route, with the zone it falls in.
 *
 * @param shipmentId  orders-service shipment id
 * @param orderNumber customer order number (one drop-off per order address)
 * @param origin      collected at the depot FC or at the seller
 * @param sellerOrgId seller organisation (seller pickups are grouped by it)
 * @param postalCode  delivery ZIP
 * @param zone        delivery zone (geo-service zone name, or ZIP fallback)
 */
public record RoutableShipment(String shipmentId, String orderNumber, PackageOrigin origin, String sellerOrgId,
                               String postalCode, String zone) {
}
