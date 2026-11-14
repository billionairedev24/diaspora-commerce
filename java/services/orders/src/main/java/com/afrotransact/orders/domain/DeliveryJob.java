package com.afrotransact.orders.domain;

import java.time.Instant;

/**
 * Who has an AfroTransact delivery (a staff member or courier, by name), how many attempts failed and the optimistic
 * lock. A shipment has no job until someone takes it ({@link #none}); every change bumps {@code version}.
 */
public record DeliveryJob(String shipmentId, String assigneeId, String assigneeName, Instant assignedAt,
                          String assignedBy, int attempts, String lastFailure, Instant createdAt, Instant updatedAt,
                          long version) {

    public static DeliveryJob none(String shipmentId, Instant now) {
        return new DeliveryJob(shipmentId, null, null, null, null, 0, null, now, now, 0);
    }

    public boolean assigned() {
        return assigneeId != null;
    }

    public DeliveryJob assignedTo(String id, String name, String by, Instant at) {
        return new DeliveryJob(shipmentId, id, name, at, by, attempts, lastFailure, createdAt, at, version + 1);
    }

    public DeliveryJob failed(String reason, Instant at) {
        return new DeliveryJob(shipmentId, assigneeId, assigneeName, assignedAt, assignedBy, attempts + 1, reason,
                createdAt, at, version + 1);
    }

    public DeliveryJob touched(Instant at) {
        return new DeliveryJob(shipmentId, assigneeId, assigneeName, assignedAt, assignedBy, attempts, lastFailure,
                createdAt, at, version + 1);
    }
}
