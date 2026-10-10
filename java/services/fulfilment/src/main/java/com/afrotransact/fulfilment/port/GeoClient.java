package com.afrotransact.fulfilment.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.GeoPoint;

/** geo-service: ZIP centroid and delivery zones covering a point (public endpoints). Empty on any failure. */
public interface GeoClient {

    Optional<GeoPoint> locateZip(String zip);

    /** Name of the active DELIVERY zone containing the point. */
    Optional<String> deliveryZoneAt(GeoPoint point);

    /**
     * Road distances from {@code from} to each point ({@code POST /v1/distance/batch}: geo's routing providers, PostGIS
     * geodesic × detour as its last resort), in the same order; empty entries for points geo could not measure, and an
     * empty list when geo is unreachable (callers fall back to straight-line order).
     */
    List<Optional<RoadDistance>> roadDistances(GeoPoint from, List<GeoPoint> to);

    /** Driving miles and minutes between two points, and geo's method. */
    record RoadDistance(double roadMiles, double durationMinutes, String method) {
    }
}
