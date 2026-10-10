package com.afrotransact.orders.domain;

/**
 * AfroTransact Delivery from the seller's store (S255), snapshotted at checkout on the shipment: where the driver
 * collects, how far it is to the customer and what was quoted (payments delivery pricing). Never re-priced.
 *
 * @param shipFromLocationId  seller-service ship-from location with AfroTransact pickup enabled
 * @param pickupLat           pickup position (latitude)
 * @param pickupLng           pickup position (longitude)
 * @param straightLineMiles   geodesic distance (geo-service, PostGIS)
 * @param distanceMiles       road miles the price is based on (geo routing provider)
 * @param distanceMethod      ROAD_GOOGLE, ROAD_OSRM or GEODESIC_DETOUR (last resort)
 * @param customerChargeMinor the delivery charge on the order (the shipment's shipping; AfroTransact's, 4200)
 * @param sellerFeeMinor      the seller's pickup-delivery fee (deducted at capture, 4250)
 * @param pricingVersion      fee-schedule version that priced it
 * @param durationMinutes     driving time pickup → customer (geo routing provider)
 */
public record LocalDelivery(String shipFromLocationId, double pickupLat, double pickupLng, double straightLineMiles,
                            double distanceMiles, String distanceMethod, long customerChargeMinor,
                            long sellerFeeMinor, int pricingVersion, double durationMinutes) {

    public LocalDelivery {
        if (shipFromLocationId == null || shipFromLocationId.isBlank()) {
            throw new IllegalArgumentException("shipFromLocationId is required");
        }
        if (customerChargeMinor < 0 || sellerFeeMinor < 0 || distanceMiles < 0 || straightLineMiles < 0) {
            throw new OrdersRuleViolation("orders.invalid-amount", "Delivery amounts and distances cannot be negative");
        }
    }
}
