package com.afrotransact.inventory.application;

import java.util.Set;

import com.afrotransact.inventory.domain.Reservation;

/**
 * Checkout reservations: RESERVE (TTL) → confirm (payment authorised) → COMMIT on shipment (SHIP),
 * or RELEASE on cancellation / payment failure, or EXPIRE automatically.
 */
public interface ReservationService {

    /** Reserves all lines in one transaction or none (HTTP 409 with per-SKU shortfalls). Idempotent per order id. */
    ReservationResult reserve(ReserveCommand command);

    Reservation get(String orderId);

    /** Payment authorised: the hold no longer expires and waits for shipment. Idempotent. */
    Reservation confirm(String orderId);

    /** Ships the reservation: on hand and reserved go down by the allocated lots; returns COGS. Idempotent. */
    CommitResult commit(String orderId);

    /**
     * Ships only the held lines of the given groups (orders: the line ids of one shipment). Idempotent: groups that
     * already shipped are replayed. 404 {@code inventory.group-not-found} for unknown groups (e.g. a reservation
     * made without groups), 409 {@code inventory.group-released} when a line was released.
     */
    CommitResult commitGroups(String orderId, Set<String> groups);

    /**
     * Releases only the held lines of the given groups (one cancelled shipment). Idempotent; 409
     * {@code inventory.group-committed} when a line already shipped.
     */
    Reservation releaseGroups(String orderId, Set<String> groups, String reason);

    /** Releases a HELD reservation back to available stock. Idempotent. */
    Reservation release(String orderId, String reason);

    /** Releases up to {@code limit} HELD reservations past their TTL (scheduled job). Returns how many. */
    int expireDue(int limit);
}
