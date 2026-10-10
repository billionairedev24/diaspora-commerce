package com.afrotransact.seller.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Reminders go out 7, 3 and 1 day(s) before {@code current_deadline}; only the nearest threshold is sent. */
public final class ReminderSchedule {

    public static final List<Integer> THRESHOLD_DAYS = List.of(1, 3, 7);

    private ReminderSchedule() {
    }

    /** The threshold (1, 3 or 7) whose window {@code now} is in, or empty before 7 days out and after the deadline. */
    public static Optional<Integer> dueThreshold(Instant deadline, Instant now) {
        if (deadline == null || !now.isBefore(deadline)) {
            return Optional.empty();
        }
        Duration left = Duration.between(now, deadline);
        return THRESHOLD_DAYS.stream().filter(d -> left.compareTo(Duration.ofDays(d)) <= 0).findFirst();
    }
}
