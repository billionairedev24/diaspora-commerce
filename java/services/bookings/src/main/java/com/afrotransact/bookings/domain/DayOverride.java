package com.afrotransact.bookings.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * An exception to the weekly hours for one date: time off / blocked date ({@code closed}) or special hours.
 *
 * @param ranges special hours for that date; ignored when closed
 */
public record DayOverride(LocalDate date, boolean closed, List<TimeRange> ranges, String note) {

    public DayOverride {
        Objects.requireNonNull(date, "date");
        List<TimeRange> sorted = new ArrayList<>(ranges == null ? List.of() : ranges);
        sorted.sort(Comparator.comparing(TimeRange::start));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).overlaps(sorted.get(i))) {
                throw new BookingsRuleViolation("bookings.invalid-hours", "Special hours overlap on " + date);
            }
        }
        if (!closed && sorted.isEmpty()) {
            throw new BookingsRuleViolation("bookings.invalid-hours",
                    "Special hours need at least one range, or mark the date as closed");
        }
        ranges = closed ? List.of() : List.copyOf(sorted);
        note = note == null || note.isBlank() ? null : note.trim();
    }

    public static DayOverride timeOff(LocalDate date, String note) {
        return new DayOverride(date, true, List.of(), note);
    }
}
