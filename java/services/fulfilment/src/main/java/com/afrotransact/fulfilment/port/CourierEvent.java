package com.afrotransact.fulfilment.port;

import java.time.Instant;

/**
 * A courier event for orders-service {@code POST /v1/shipments/{id}/courier-events}.
 *
 * @param eventId          idempotency id (driver event id + step)
 * @param status           picked_up | in_transit | out_for_delivery | delivered | delivery_failed | returned
 * @param occurredAt       device time of the action
 * @param driverId         driver subject (actor when relayed by fulfilment-service)
 * @param photoAssetId     proof photo
 * @param handoverMethod   customer | someone_else | safe_place
 * @param handoverCode     the customer's 4-digit code (never stored)
 * @param recipientName    recipient when not the customer
 * @param relationship     recipient's relationship
 * @param signatureAssetId recipient's signature
 * @param safePlace        safe place
 * @param failureReason    reason of a failed attempt
 * @param latitude         driver position
 * @param longitude        driver position
 * @param note             free text
 */
public record CourierEvent(String eventId, String status, Instant occurredAt, String driverId, String photoAssetId,
                           String handoverMethod, String handoverCode, String recipientName, String relationship,
                           String signatureAssetId, String safePlace, String failureReason, Double latitude,
                           Double longitude, String note) {

    public static CourierEvent step(String eventId, String status, Instant at, String driverId, Double lat,
                                    Double lon, String note) {
        return new CourierEvent(eventId, status, at, driverId, null, null, null, null, null, null, null, null, lat,
                lon, note);
    }

    /** Copy without the handover code, safe to store. */
    public CourierEvent redacted() {
        return new CourierEvent(eventId, status, occurredAt, driverId, photoAssetId, handoverMethod,
                handoverCode == null ? null : "****", recipientName, relationship, signatureAssetId, safePlace,
                failureReason, latitude, longitude, note);
    }
}
