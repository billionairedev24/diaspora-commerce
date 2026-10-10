package com.afrotransact.bookings.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything needed to list one team member's free start times on one local date.
 *
 * @param date            local date in the provider's time zone
 * @param override        time off or special hours for that date
 * @param busy            the member's existing appointments (with their buffers)
 * @param bookingsThatDay the member's active bookings starting on that date (daily limit)
 */
public record SlotQuery(LocalDate date, ZoneId zone, int durationMinutes, BookingRules rules, WeeklyHours hours,
                        Optional<DayOverride> override, List<BusyInterval> busy, int bookingsThatDay, Instant now) {

    public SlotQuery {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(hours, "hours");
        Objects.requireNonNull(now, "now");
        override = override == null ? Optional.empty() : override;
        busy = busy == null ? List.of() : List.copyOf(busy);
        if (durationMinutes <= 0) {
            throw new IllegalArgumentException("durationMinutes must be positive");
        }
    }
}
