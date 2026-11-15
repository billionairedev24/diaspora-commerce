package com.afrotransact.seller.api;

import java.util.Optional;

/**
 * What the orders module asks seller in-process about where a seller ships from: one location by id
 * ({@code GET /v1/ship-from-locations/{id}}) and an organisation's default ship-from address
 * ({@code GET /v1/orgs/{orgId}/default-ship-from-location}); scope {@code sellers:ship-from.read}, entered under the
 * caller's in-process principal. A caller without the scope is refused with {@code ModuleApiException} status 403.
 */
public interface ShipFromAddressApi {

    /** @return the location, empty when there is no such location or the id is not a location id (HTTP 404 or 400) */
    Optional<ShipFromPlace> location(String locationId);

    /** @return the organisation's default ship-from address, empty when it has none (HTTP 404) */
    Optional<ShipFromPlace> defaultLocation(String orgId);

    /**
     * @param pickupEnabled AfroTransact Delivery pickup is enabled at the location
     * @param lat           the geocoded latitude, null until geo located it
     * @param lng           the geocoded longitude, null until geo located it
     */
    record ShipFromPlace(String id, String orgId, String line1, String city, String state, String postalCode,
                         String country, boolean pickupEnabled, Double lat, Double lng) {
    }
}
