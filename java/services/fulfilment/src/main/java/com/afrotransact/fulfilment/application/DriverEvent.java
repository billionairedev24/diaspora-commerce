package com.afrotransact.fulfilment.application;

import java.time.Instant;

import com.afrotransact.fulfilment.domain.ContactKind;
import com.afrotransact.fulfilment.domain.DeliveryProblem;
import com.afrotransact.fulfilment.domain.DriverEventType;
import com.afrotransact.fulfilment.domain.InspectionReport;
import com.afrotransact.fulfilment.domain.PickupProblem;
import com.afrotransact.fulfilment.domain.ProofOfDelivery;
import com.afrotransact.fulfilment.domain.SosKind;

/**
 * One action queued in the driver app's on-device outbox.
 *
 * @param eventId       client-generated id (idempotency key)
 * @param sequence      per-device sequence number, 1, 2, 3 … (events are applied strictly in this order)
 * @param type          wire type, e.g. {@code delivery.completed} (see {@link DriverEventType})
 * @param routeId       route the action belongs to
 * @param stopId        stop, for stop actions
 * @param capturedAt    device time of the action
 * @param lat           device position
 * @param lon           device position
 * @param note          free text
 * @param inspection    inspection.submitted
 * @param scan          loadout.scanned, stop.scanned, return.scanned
 * @param pickup        pickup.confirmed
 * @param pickupProblem pickup.problem
 * @param contact       customer.contacted (CALL, TEXT, WAIT_STARTED)
 * @param delivery      delivery.completed (proof of delivery)
 * @param problem       delivery.problem
 * @param odometer      shift.ended
 * @param sos           sos.raised
 */
public record DriverEvent(String eventId, long sequence, String type, String routeId, String stopId,
                          Instant capturedAt, Double lat, Double lon, String note, InspectionReport inspection,
                          Scan scan, PickupConfirmation pickup, PickupProblem pickupProblem, ContactKind contact,
                          ProofOfDelivery delivery, DeliveryProblem problem, Integer odometer, SosKind sos) {

    /** A label scan; {@code manual} when typed in instead of scanned (flagged for dispatch). */
    public record Scan(String label, boolean manual) {
    }

    /** Seller pickup confirmation. */
    public record PickupConfirmation(String handedOverBy, String missingReason) {
    }

    public boolean isSos() {
        return DriverEventType.SOS_RAISED.wire().equals(type);
    }

    /** Copy safe to store: the customer's handover code is masked. */
    public DriverEvent redacted() {
        if (delivery == null || delivery.handoverCode() == null) {
            return this;
        }
        ProofOfDelivery masked = new ProofOfDelivery(delivery.method(), "****", delivery.recipientName(),
                delivery.relationship(), delivery.signatureAssetId(), delivery.safePlace(), delivery.photo());
        return new DriverEvent(eventId, sequence, type, routeId, stopId, capturedAt, lat, lon, note, inspection,
                scan, pickup, pickupProblem, contact, masked, problem, odometer, sos);
    }
}
