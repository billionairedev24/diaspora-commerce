package com.afrotransact.bookings.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a booking state change: the statuses and the money instructions to carry out.
 *
 * @param rescheduled   the appointment moved to a new time
 * @param balanceUnpaid the balance could not be collected automatically: the booking was flagged for the provider's
 *                      and the customer's attention
 */
public record Transition(BookingStatus from, BookingStatus to, List<MoneyMove> moves, boolean rescheduled,
                         boolean balanceUnpaid) {

    public Transition {
        moves = List.copyOf(moves);
    }

    public Transition(BookingStatus from, BookingStatus to, List<MoneyMove> moves, boolean rescheduled) {
        this(from, to, moves, rescheduled, false);
    }

    public boolean changed() {
        return from != to || rescheduled || !moves.isEmpty() || balanceUnpaid;
    }

    /** The same transition with more money instructions, carried out after its own. */
    public Transition plus(List<MoneyMove> more) {
        if (more.isEmpty()) {
            return this;
        }
        List<MoneyMove> all = new ArrayList<>(moves);
        all.addAll(more);
        return new Transition(from, to, all, rescheduled, balanceUnpaid);
    }
}
