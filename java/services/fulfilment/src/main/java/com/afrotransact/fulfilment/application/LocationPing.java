package com.afrotransact.fulfilment.application;

import java.time.Instant;

/**
 * An explicit driver-app location ping ({@code POST /v1/driver/location}).
 *
 * @param capturedAt device time of the fix (null = now)
 */
public record LocationPing(String deviceId, double lat, double lon, Double accuracyM, Instant capturedAt) {
}
