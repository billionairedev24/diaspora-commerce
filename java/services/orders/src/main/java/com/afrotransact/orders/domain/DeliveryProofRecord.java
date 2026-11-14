package com.afrotransact.orders.domain;

import java.time.Instant;
import java.util.List;

/**
 * The proof of delivery AfroTransact staff attach when they mark a shipment delivered in the Delivery module: at least
 * one photo (media asset ids of purpose proof-photo, a private bucket served by signed links), an optional recipient
 * name and note, the time and the staff member's name. Coordinates are only present when the device had them.
 *
 * <p>Immutable once saved: the only change is {@link #corrected}, an admin correction that counts itself and says who
 * made it (the previous values go to the audit trail).
 */
public record DeliveryProofRecord(String id, String shipmentId, List<String> photoAssetIds, String recipientName,
                                  String note, Double latitude, Double longitude, Instant deliveredAt,
                                  String recordedById, String recordedByName, Instant correctedAt,
                                  String correctedById, String correctedByName, int corrections, long version,
                                  Instant createdAt) {

    public DeliveryProofRecord {
        photoAssetIds = List.copyOf(photoAssetIds);
    }

    /** A first-time proof. */
    public static DeliveryProofRecord of(String id, String shipmentId, DeliveryProofDraft draft, Instant at,
                                         String staffId, String staffName) {
        return new DeliveryProofRecord(id, shipmentId, draft.photoAssetIds(), draft.recipientName(), draft.note(),
                draft.latitude(), draft.longitude(), at, staffId, staffName, null, null, null, 0, 0, at);
    }

    /** The same proof after an admin correction (photos, recipient, note, position; the delivery time is kept). */
    public DeliveryProofRecord corrected(DeliveryProofDraft draft, Instant at, String staffId, String staffName) {
        return new DeliveryProofRecord(id, shipmentId, draft.photoAssetIds(), draft.recipientName(), draft.note(),
                draft.latitude(), draft.longitude(), deliveredAt, recordedById, recordedByName, at, staffId,
                staffName, corrections + 1, version, createdAt);
    }
}
