package com.afrotransact.bookings.domain;

import java.time.Instant;
import java.util.Objects;

/** Time a team member is already taken: an appointment (or proposed time) plus its buffer, end exclusive. */
public record BusyInterval(Instant start, Instant end) {

    public BusyInterval {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
    }

    public boolean overlaps(Instant otherStart, Instant otherEnd) {
        return otherStart.isBefore(end) && start.isBefore(otherEnd);
    }
}
