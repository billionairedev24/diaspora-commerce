package com.afrotransact.ai.port;

import java.util.List;

/**
 * bookings-service {@code GET /v1/services/search?q=&zip=}: bookable services (braiders, caterers, tailors …).
 * Returns an empty list while the bookings service is not deployed.
 */
public interface ServiceDirectory {

    List<Listing> find(String query, String zip, String bearerToken);

    /** A bookable service. */
    record Listing(String serviceId, String name, String providerName, String description, long priceFromMinor,
                   String currency, long depositMinor, double rating, String nextAvailable, String location) {
    }
}
