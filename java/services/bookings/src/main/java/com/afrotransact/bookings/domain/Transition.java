package com.afrotransact.bookings.domain;

import java.util.List;

/**
 * Result of a booking state change: the statuses and the money instructions to carry out.
 *
 * @param rescheduled the appointment moved to a new time
 */
public record Transition(BookingStatus from, BookingStatus to, List<MoneyMove> moves, boolean rescheduled) {

    public Transition {
        moves = List.copyOf(moves);
    }

    public boolean changed() {
        return from != to || rescheduled || !moves.isEmpty();
    }
}
