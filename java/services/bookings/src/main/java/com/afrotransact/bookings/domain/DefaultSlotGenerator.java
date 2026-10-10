package com.afrotransact.bookings.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The design's slot rules: start times every {@code step} minutes inside each working range (breaks split ranges),
 * the whole appointment must fit before the range ends, nothing earlier than the minimum notice or further than the
 * booking horizon, nothing on days off or once the daily limit is reached, and no overlap with an existing
 * appointment including the buffer on both sides. Local wall-clock times are converted in the provider's zone, so
 * daylight-saving changes are handled: start times that do not exist that day are skipped.
 */
public class DefaultSlotGenerator implements SlotGenerator {

    @Override
    public List<Slot> slots(SlotQuery q) {
        List<TimeRange> ranges = q.override()
                .map(o -> o.closed() ? List.<TimeRange>of() : o.ranges())
                .orElseGet(() -> q.hours().rangesFor(q.date().getDayOfWeek()));
        if (ranges.isEmpty()) {
            return List.of();
        }
        BookingRules rules = q.rules();
        Instant dayStart = q.date().atStartOfDay(q.zone()).toInstant();
        if (dayStart.isAfter(q.now().plus(Duration.ofDays(rules.horizonDays())))) {
            return List.of();
        }
        if (q.bookingsThatDay() >= rules.maxPerDay()) {
            return List.of();
        }
        Instant earliest = q.now().plus(Duration.ofMinutes(rules.leadMinutes()));
        Duration duration = Duration.ofMinutes(q.durationMinutes());
        Duration buffer = Duration.ofMinutes(rules.bufferMinutes());
        List<Slot> out = new ArrayList<>();
        for (TimeRange range : ranges) {
            Instant rangeEnd = q.date().atTime(range.end()).atZone(q.zone()).toInstant();
            for (int m = range.startMinute(); m + q.durationMinutes() <= range.endMinute(); m += rules.stepMinutes()) {
                LocalDateTime local = q.date().atTime(LocalTime.ofSecondOfDay(m * 60L));
                ZonedDateTime zoned = ZonedDateTime.ofLocal(local, q.zone(), null);
                if (!zoned.toLocalDateTime().equals(local)) {
                    continue;
                }
                Instant start = zoned.toInstant();
                Instant end = start.plus(duration);
                if (start.isBefore(earliest) || end.isAfter(rangeEnd)) {
                    continue;
                }
                Instant blockEnd = end.plus(buffer);
                boolean taken = q.busy().stream().anyMatch(b -> b.overlaps(start, blockEnd));
                if (!taken) {
                    out.add(new Slot(start, end));
                }
            }
        }
        return List.copyOf(out);
    }
}
