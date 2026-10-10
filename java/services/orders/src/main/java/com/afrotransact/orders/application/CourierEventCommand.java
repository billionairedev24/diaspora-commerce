package com.afrotransact.orders.application;

import java.time.Instant;

import com.afrotransact.orders.domain.CourierProof;

/**
 * A driver-app event (queued offline with its own id).
 *
 * @param eventId    idempotency id from the app
 * @param status     picked_up | in_transit | out_for_delivery | delivered | delivery_failed | returned
 * @param occurredAt when the driver did it
 * @param proof      photo, handover method / code, recipient, safe place, failure reason, position
 * @param note       free text
 * @param driverId   the driver fulfilment-service relays for (SERVICE callers with delivery:relay); ignored for
 *                   driver tokens
 */
public record CourierEventCommand(String eventId, String status, Instant occurredAt, CourierProof proof,
                                  String note, String driverId) {
}
