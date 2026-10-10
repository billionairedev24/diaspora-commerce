package com.afrotransact.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * All lines of one order, reserved together or not at all. The order id is the idempotency key: a retry for the
 * same order returns this reservation instead of reserving again.
 *
 * @param expiresAt when a HELD reservation is released automatically; null once payment confirmed the order
 */
public record Reservation(UUID id, String orderId, ReservationStatus status, String idempotencyKey,
                          Instant expiresAt, Instant createdAt, Instant updatedAt, List<ReservationLine> lines,
                          long version) {

    public Reservation {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static Reservation hold(UUID id, String orderId, String idempotencyKey, Instant now, Instant expiresAt) {
        return new Reservation(id, orderId, ReservationStatus.HELD, idempotencyKey, expiresAt, now, now, List.of(), 0);
    }

    public Reservation withLines(List<ReservationLine> newLines) {
        return new Reservation(id, orderId, status, idempotencyKey, expiresAt, createdAt, updatedAt, newLines, version);
    }

    /** Payment authorised: keep the hold until shipment instead of letting the checkout TTL release it. */
    public Reservation confirm(Instant now) {
        requireHeld("confirm");
        return new Reservation(id, orderId, status, idempotencyKey, null, createdAt, now, lines, version);
    }

    public Reservation commit(Instant now) {
        requireHeld("commit");
        return to(ReservationStatus.COMMITTED, now);
    }

    public Reservation release(Instant now) {
        requireHeld("release");
        return to(ReservationStatus.RELEASED, now);
    }

    public Reservation expire(Instant now) {
        requireHeld("expire");
        return to(ReservationStatus.EXPIRED, now);
    }

    public boolean isHeld() {
        return status == ReservationStatus.HELD;
    }

    public boolean expiredAt(Instant now) {
        return isHeld() && expiresAt != null && !expiresAt.isAfter(now);
    }

    /** Total landed cost of everything allocated, per currency is left to the caller. */
    public long costMinor() {
        return lines.stream().mapToLong(ReservationLine::costMinor).sum();
    }

    private Reservation to(ReservationStatus next, Instant now) {
        return new Reservation(id, orderId, next, idempotencyKey, expiresAt, createdAt, now, lines, version);
    }

    private void requireHeld(String action) {
        if (status != ReservationStatus.HELD) {
            throw new InventoryStateConflict("inventory.reservation-not-held", "Cannot " + action + " reservation for "
                    + "order " + orderId + ": it is " + status);
        }
    }
}
