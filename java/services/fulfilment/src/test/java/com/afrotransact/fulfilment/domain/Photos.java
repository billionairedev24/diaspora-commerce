package com.afrotransact.fulfilment.domain;

import java.time.Instant;

/** Test photo captures. */
final class Photos {

    static final Instant AT = Instant.parse("2026-10-10T19:30:00Z");
    static final GeoPoint ADDRESS = new GeoPoint(29.7376, -95.5134);

    private Photos() {
    }

    static PhotoCapture live(String routeId) {
        return new PhotoCapture("asset-" + routeId, CaptureSource.LIVE_CAMERA, AT, ADDRESS.lat(), ADDRESS.lon(), 5.0,
                routeId, "ab12");
    }

    static PhotoCapture liveAt(String routeId, double lat, double lon) {
        return new PhotoCapture("asset-far", CaptureSource.LIVE_CAMERA, AT, lat, lon, 5.0, routeId, "ab12");
    }

    static PhotoCapture gallery(String routeId) {
        return new PhotoCapture("asset-gallery", CaptureSource.GALLERY, AT, ADDRESS.lat(), ADDRESS.lon(), 5.0,
                routeId, "ab12");
    }
}
