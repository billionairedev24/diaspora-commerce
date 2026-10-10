package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Where a ship-from address is, as geocoded by geo-service.
 *
 * @param precision ADDRESS (street geocoded) or ZIP (ZIP centroid fallback); POINT when given as coordinates
 */
public record GeoPosition(double lat, double lng, String precision, Instant geocodedAt) {

    public GeoPosition {
        if (Double.isNaN(lat) || Double.isNaN(lng) || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new IllegalArgumentException("Coordinates out of range");
        }
        Objects.requireNonNull(precision, "precision");
    }

    /** A ZIP centroid is too coarse to send a driver to. */
    public boolean streetLevel() {
        return !"ZIP".equals(precision);
    }
}
