package com.afrotransact.fulfilment.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A driver's last known position (driver-app GPS on synced events, or an explicit location ping).
 *
 * @param source SYNC (position of a synced driver event) or PING ({@code POST /v1/driver/location})
 */
public record DriverPosition(String driverId, GeoPoint point, Instant recordedAt, Double accuracyM, String source,
                             String deviceId) {

    public DriverPosition {
        Objects.requireNonNull(driverId, "driverId");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (accuracyM != null && (accuracyM < 0 || accuracyM.isNaN())) {
            throw new FulfilmentRuleViolation("fulfilment.invalid-position", "accuracyM must be ≥ 0");
        }
    }

    /** Positions older than {@code staleAfter} are ignored when looking for the nearest driver. */
    public boolean fresh(Instant now, Duration staleAfter) {
        return !recordedAt.isBefore(now.minus(staleAfter));
    }
}
