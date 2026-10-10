package com.afrotransact.seller.port;

import java.util.Optional;

/** geo-service AfroTransact Delivery zones ({@code GET /v1/zones/covering?type=DELIVERY&withinMiles=}). */
public interface DeliveryZones {

    /**
     * The active DELIVERY zone containing the point, else the nearest one whose edge is at most {@code withinMiles}
     * away.
     *
     * @throws GeoUnavailableException when geo-service cannot be reached
     */
    Optional<ZoneMatch> nearest(double lat, double lng, double withinMiles);

    /** A delivery zone near a point. */
    record ZoneMatch(String name, boolean inside, double distanceMiles) {
    }
}
