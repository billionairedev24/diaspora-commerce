package com.afrotransact.orders.port;

import com.afrotransact.orders.domain.ShipTo;

/**
 * geo-service {@code POST /v1/distance} (public): driving distance and duration from a pickup position to the delivery
 * address (routing providers Google Routes API / OSRM in priority order; PostGIS geodesic × detour only as the last
 * resort), plus the straight-line distance. The address's stored coordinates are sent when known; otherwise geo geocodes the
 * street address (ZIP centroid as a fallback).
 */
public interface DistanceClient {

    Distance measure(double fromLat, double fromLng, ShipTo to);

    /**
     * Miles and minutes between the two places.
     *
     * @param method ROAD_GOOGLE, ROAD_OSRM or GEODESIC_DETOUR (last resort)
     */
    record Distance(double straightLineMiles, double roadMiles, double durationMinutes, String method) {
    }
}
