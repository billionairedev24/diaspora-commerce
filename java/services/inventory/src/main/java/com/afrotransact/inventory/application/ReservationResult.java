package com.afrotransact.inventory.application;

import com.afrotransact.inventory.domain.Reservation;

/** A reservation and whether it was created by this call or returned from an earlier one (idempotent replay). */
public record ReservationResult(Reservation reservation, boolean replayed) {
}
