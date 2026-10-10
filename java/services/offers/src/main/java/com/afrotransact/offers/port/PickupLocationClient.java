package com.afrotransact.offers.port;

import java.util.Optional;

/**
 * seller-service ship-from locations ({@code GET /v1/ship-from-locations/{id}}, offers' client-credentials token with
 * {@code sellers:ship-from.read}): an AFROTRANSACT_DELIVERY offer must reference one of its seller's locations with
 * AfroTransact pickup enabled (S255).
 */
public interface PickupLocationClient {

    /** The location, or empty when seller-service doesn't know it. */
    Optional<PickupLocation> find(String locationId);

    /** What offers needs of a location. */
    record PickupLocation(String id, String orgId, boolean pickupEnabled, String city, String region, String country,
                          String postalCode) {
    }

    /** seller-service could not be reached or answered with a server error. */
    class SellerUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public SellerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
