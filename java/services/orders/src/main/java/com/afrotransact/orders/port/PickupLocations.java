package com.afrotransact.orders.port;

import java.util.Optional;

/**
 * Where an AFROTRANSACT_DELIVERY offer is collected (S255): offers-service {@code GET /v1/public/offers/{offerId}}
 * gives the offer's {@code shipFromLocationId}, seller-service {@code GET /v1/ship-from-locations/{id}} (orders'
 * service token, {@code sellers:ship-from.read}) its position and whether AfroTransact pickup is still enabled.
 */
public interface PickupLocations {

    /** The offer's pickup location; empty when the offer names none or seller-service doesn't know it. */
    Optional<PickupLocation> forOffer(String offerId);

    /** A seller's ship-from location (position null when it was never geocoded). */
    record PickupLocation(String locationId, String orgId, boolean pickupEnabled, Double lat, Double lng) {
    }
}
