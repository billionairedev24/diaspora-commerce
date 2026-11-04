package com.afrotransact.orders.domain;

import java.time.Instant;

/**
 * One recorded shipment transition (status history row).
 *
 * @param shipmentId shipment
 * @param eventId    idempotency id: Idempotency-Key, carrier event id or driver-app event id
 * @param from       previous status
 * @param to         new status
 * @param actorKind  actor type
 * @param actorId    user / client / carrier id
 * @param actorOrg   organisation the actor acted for
 * @param reason     free-text reason (required for cancellations and failures)
 * @param source     api | carrier | driver | system
 * @param occurredAt when it happened
 * @param proof      the driver app's proof of delivery (handover code redacted), null when there is none
 */
public record StatusChange(String shipmentId, String eventId, ShipmentStatus from, ShipmentStatus to,
                           ActorKind actorKind, String actorId, String actorOrg, String reason,
                           TransitionSource source, Instant occurredAt, CourierProof proof) {

    public StatusChange(String shipmentId, String eventId, ShipmentStatus from, ShipmentStatus to,
                        ActorKind actorKind, String actorId, String actorOrg, String reason,
                        TransitionSource source, Instant occurredAt) {
        this(shipmentId, eventId, from, to, actorKind, actorId, actorOrg, reason, source, occurredAt, null);
    }
}
