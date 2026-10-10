package com.afrotransact.fulfilment.domain;

import java.time.Instant;

/**
 * A photo taken in the driver app, uploaded to media-service, with the capture metadata the app stamps on it.
 *
 * @param assetId      media-service asset id of the uploaded image
 * @param source       live in-app camera or something else (rejected)
 * @param capturedAt   device time of the shutter
 * @param lat          GPS latitude at capture
 * @param lon          GPS longitude at capture
 * @param accuracyM    GPS accuracy in metres
 * @param stampRouteId route id burnt into the photo stamp
 * @param sha256       SHA-256 of the image bytes computed on the device
 */
public record PhotoCapture(String assetId, CaptureSource source, Instant capturedAt, Double lat, Double lon,
                           Double accuracyM, String stampRouteId, String sha256) {

    /** Requires an uploaded asset taken with the live camera; {@code what} names the photo in messages. */
    public void requireLive(String what) {
        if (assetId == null || assetId.isBlank()) {
            throw new FulfilmentRuleViolation("fulfilment.photo-required", what + " photo is required");
        }
        if (source != CaptureSource.LIVE_CAMERA) {
            throw new FulfilmentRuleViolation("fulfilment.photo-not-live",
                    what + " photo must be taken with the live in-app camera; gallery uploads are not accepted");
        }
        if (capturedAt == null) {
            throw new FulfilmentRuleViolation("fulfilment.photo-stamp-missing", what + " photo has no capture time");
        }
    }

    /** Requires the full stamp: time, GPS and the route id. */
    public void requireStamp(String what, String routeId) {
        requireLive(what);
        if (lat == null || lon == null) {
            throw new FulfilmentRuleViolation("fulfilment.photo-stamp-missing", what + " photo has no GPS stamp");
        }
        if (stampRouteId == null || !stampRouteId.equals(routeId)) {
            throw new FulfilmentRuleViolation("fulfilment.photo-stamp-route-mismatch",
                    what + " photo must be stamped with route " + routeId);
        }
    }

    public GeoPoint position() {
        return GeoPoint.of(lat, lon);
    }

    static boolean present(PhotoCapture p) {
        return p != null && p.assetId() != null && !p.assetId().isBlank();
    }
}
