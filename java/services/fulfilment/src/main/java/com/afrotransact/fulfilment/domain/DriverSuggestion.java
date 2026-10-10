package com.afrotransact.fulfilment.domain;

import java.time.Instant;

/**
 * An available driver for a seller pickup, nearest first (S255): on shift (fresh position), an assigned or in-progress
 * route today in the pickup's zone, a passed vehicle inspection and room on the route.
 *
 * @param straightLineMiles geodesic distance from the driver's position to the pickup (PostGIS KNN)
 * @param roadMiles         driving distance (geo routing provider); null when geo could not answer
 * @param distanceMethod    geo's method (ROAD_GOOGLE, ROAD_OSRM, GEODESIC_DETOUR) or STRAIGHT_LINE when geo was down
 */
public record DriverSuggestion(String driverId, String routeId, String routeName, String zone, String routeStatus,
                               double straightLineMiles, Double roadMiles, Double durationMinutes,
                               String distanceMethod, Instant positionAt, int packagesOnRoute, int capacityLeft) {

    /** Miles used to rank: road miles when known, else straight line. */
    public double rankMiles() {
        return roadMiles != null ? roadMiles : straightLineMiles;
    }
}
