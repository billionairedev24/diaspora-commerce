package com.afrotransact.orders.domain;

/**
 * Proof the driver app attaches to a courier event.
 *
 * @param photoAssetId      media asset of the proof photo (time and GPS stamped by the app)
 * @param handoverMethod    how a delivery was handed over
 * @param handoverCode      4-digit code the customer gives the driver
 * @param recipientName     who received it when not the customer
 * @param relationship      relationship of that person to the customer
 * @param signatureAssetId  media asset of the drawn signature
 * @param safePlace         where it was left
 * @param failureReason     why a delivery attempt failed
 * @param latitude          driver position
 * @param longitude         driver position
 */
public record CourierProof(String photoAssetId, HandoverMethod handoverMethod, String handoverCode,
                           String recipientName, String relationship, String signatureAssetId, String safePlace,
                           String failureReason, Double latitude, Double longitude) {

    public static CourierProof none() {
        return new CourierProof(null, null, null, null, null, null, null, null, null, null);
    }

    /** Copy without the handover code, safe to store in the history. */
    public CourierProof redacted() {
        return new CourierProof(photoAssetId, handoverMethod, handoverCode == null ? null : "****", recipientName,
                relationship, signatureAssetId, safePlace, failureReason, latitude, longitude);
    }
}
