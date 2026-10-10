package com.afrotransact.deliveryruns.port;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.RouteEstimate;

/**
 * geo-service (local port 8205): ZIP centroids, zone coverage and road distances. Every method throws
 * {@link GeoUnavailableException} when geo-service cannot answer.
 */
public interface GeoGateway {

    /** Centre of a 5-digit ZIP ({@code POST /v1/geocode} with a postal code); empty when geo knows no such ZIP. */
    Optional<GeoPoint> locateZip(String zip);

    /** Ids of the active zones of {@code zoneType} containing the point ({@code GET /v1/zones/covering}). */
    Set<String> zonesCovering(GeoPoint point, String zoneType);

    /**
     * Straight-line miles from {@code from} to each destination ({@code POST /v1/distance/batch}), same order;
     * a destination geo cannot measure is null.
     */
    List<Double> straightLineMiles(GeoPoint from, List<GeoPoint> to);

    /** Driving miles and minutes ({@code POST /v1/distance}). */
    RouteEstimate route(GeoPoint from, GeoPoint to);
}
