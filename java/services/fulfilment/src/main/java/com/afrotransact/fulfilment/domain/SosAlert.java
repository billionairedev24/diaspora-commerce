package com.afrotransact.fulfilment.domain;

import java.time.Instant;

/** An SOS raised by a driver; dispatch acknowledges it. Keyed by the app's event id (idempotent). */
public record SosAlert(String eventId, String driverId, String routeId, SosKind kind, GeoPoint location, String note,
                       Instant raisedAt, Instant receivedAt, String acknowledgedBy, Instant acknowledgedAt) {
}
