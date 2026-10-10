package com.afrotransact.deliveryruns.domain;

/** WGS84 coordinates. */
public record GeoPoint(double lat, double lng) {

    public GeoPoint {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180 || Double.isNaN(lat) || Double.isNaN(lng)) {
            throw new RuleViolation("delivery-runs.invalid-coordinates", "Latitude or longitude out of range");
        }
    }
}
