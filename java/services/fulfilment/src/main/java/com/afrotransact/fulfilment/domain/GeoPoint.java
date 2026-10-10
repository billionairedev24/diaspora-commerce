package com.afrotransact.fulfilment.domain;

/** A WGS84 position. */
public record GeoPoint(double lat, double lon) {

    private static final double EARTH_RADIUS_M = 6_371_008.8;

    public GeoPoint {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new FulfilmentRuleViolation("fulfilment.invalid-position", "Latitude or longitude out of range");
        }
    }

    /** Builds a point when both coordinates are present, otherwise null. */
    public static GeoPoint of(Double lat, Double lon) {
        return lat == null || lon == null ? null : new GeoPoint(lat, lon);
    }

    /** Great-circle (haversine) distance in metres. */
    public double distanceMetersTo(GeoPoint other) {
        double dLat = Math.toRadians(other.lat - lat);
        double dLon = Math.toRadians(other.lon - lon);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(other.lat))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
