package com.afrotransact.orders.api;

import java.time.Instant;

/**
 * What the fulfilment module asks orders in-process about a shipment: the status transitions it drives, the driver-app events it
 * relays and the carrier label it records ({@code POST /v1/shipments/{id}/transitions}, {@code .../courier-events},
 * {@code .../tracking}). Entered under the caller's in-process principal: orders' own checks apply (courier events need
 * {@code delivery:relay}; a step that is not the caller's is {@code ModuleApiException} 403 {@code orders.transition-forbidden},
 * an invalid one 409 {@code orders.invalid-transition}).
 */
public interface ShipmentApi {

    /** Moves a shipment to another status; the optional key makes retries safe (same key, same result). */
    void transition(String shipmentId, String to, String reason, String idempotencyKey);

    /** Relays a driver-app event with proof; replays of the same event id are ignored. */
    void courierEvent(String shipmentId, CourierEvent event);

    /** Records the carrier label of a carrier shipment. */
    void attachTracking(String shipmentId, String carrier, String trackingNumber);

    /**
     * @param status         picked_up | in_transit | out_for_delivery | delivered | delivery_failed | returned
     * @param driverId       the driver the event is from (the relay names the actor)
     * @param handoverMethod customer | someone_else | safe_place
     */
    record CourierEvent(String eventId, String status, Instant occurredAt, String note, String driverId, Proof proof) {
    }

    /** Proof of a delivery attempt; every field is optional. */
    record Proof(String photoAssetId, String handoverMethod, String handoverCode, String recipientName,
                 String relationship, String signatureAssetId, String safePlace, String failureReason, Double latitude,
                 Double longitude) {
    }
}
