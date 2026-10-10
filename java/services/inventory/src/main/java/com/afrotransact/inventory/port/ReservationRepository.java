package com.afrotransact.inventory.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.inventory.domain.Reservation;

/** Reservations with their lines and lot allocations. */
public interface ReservationRepository {

    /**
     * Inserts the reservation header unless one exists for the order ({@code ON CONFLICT DO NOTHING}).
     * A concurrent claim for the same order blocks on the unique index until the first transaction ends.
     *
     * @return true when this transaction now owns the order's reservation
     */
    boolean claim(Reservation header);

    /** Inserts lines and allocations of a claimed reservation. */
    void insertLines(Reservation reservation);

    Optional<Reservation> findByOrderId(String orderId);

    /** {@code SELECT … FOR UPDATE} of the reservation for an order. */
    Optional<Reservation> lockByOrderId(String orderId);

    /** Locks up to {@code limit} HELD reservations past their expiry with {@code FOR UPDATE SKIP LOCKED}. */
    List<Reservation> lockExpired(Instant now, int limit);

    /** Stores status and expiry if the version matches. */
    Reservation update(Reservation reservation);
}
