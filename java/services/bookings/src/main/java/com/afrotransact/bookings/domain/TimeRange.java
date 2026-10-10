package com.afrotransact.bookings.domain;

import java.time.LocalTime;
import java.util.Objects;

/** A local wall-clock range within one day, e.g. 10:00–14:00 (end exclusive). */
public record TimeRange(LocalTime start, LocalTime end) {

    public TimeRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new BookingsRuleViolation("bookings.invalid-hours", "A time range must end after it starts ("
                    + start + "–" + end + ")");
        }
    }

    public static TimeRange of(String start, String end) {
        return new TimeRange(LocalTime.parse(start), LocalTime.parse(end));
    }

    public int startMinute() {
        return start.toSecondOfDay() / 60;
    }

    public int endMinute() {
        return end.toSecondOfDay() / 60;
    }

    public boolean overlaps(TimeRange other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }
}
