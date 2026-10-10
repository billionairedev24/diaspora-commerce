package com.afrotransact.deliveryruns.domain;

/**
 * One-way drive from the run origin (FC / hub) to the location's centre, from geo-service.
 *
 * @param roadMiles    driving miles
 * @param driveMinutes driving minutes
 * @param method       geo's method ({@code ROAD_GOOGLE}, {@code ROAD_OSRM} or {@code GEODESIC_DETOUR})
 */
public record RouteEstimate(double roadMiles, double driveMinutes, String method) {

    public RouteEstimate {
        if (roadMiles < 0 || driveMinutes < 0) {
            throw new RuleViolation("delivery-runs.invalid-route", "Route distance and time cannot be negative");
        }
        method = method == null ? "UNKNOWN" : method;
    }
}
