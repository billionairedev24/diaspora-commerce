package com.afrotransact.inventory.application;

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

    /** Releases a HELD reservation back to available stock. Idempotent. */
    Reservation release(String orderId, String reason);

    /** Releases up to {@code limit} HELD reservations past their TTL (scheduled job). Returns how many. */
    int expireDue(int limit);
}
