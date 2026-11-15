package com.afrotransact.bookings.domain;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Working hours per weekday. Several ranges on one day model breaks (10:00–14:00 and 14:30–18:00 is a
 * 30-minute break). A day with no ranges is closed.
 */
public record WeeklyHours(Map<DayOfWeek, List<TimeRange>> days) {

    public WeeklyHours {
        Map<DayOfWeek, List<TimeRange>> copy = new EnumMap<>(DayOfWeek.class);
        if (days != null) {
            for (Map.Entry<DayOfWeek, List<TimeRange>> e : days.entrySet()) {
                List<TimeRange> ranges = new ArrayList<>(e.getValue() == null ? List.of() : e.getValue());
                ranges.sort(Comparator.comparing(TimeRange::start));
                for (int i = 1; i < ranges.size(); i++) {
                    if (ranges.get(i - 1).overlaps(ranges.get(i))) {
                        throw new BookingsRuleViolation("bookings.invalid-hours", "Working hours overlap on "
                                + e.getKey(), Map.of(e.getKey().name(), "Overlapping ranges"));
                    }
                }
                if (!ranges.isEmpty()) {
                    copy.put(e.getKey(), List.copyOf(ranges));
                }
            }
        }
        days = Map.copyOf(copy);
    }

    public static WeeklyHours closed() {
        return new WeeklyHours(Map.of());
    }

    public List<TimeRange> rangesFor(DayOfWeek day) {
        return days.getOrDefault(day, List.of());
    }

    /**
     * When at least one of these members is working: per weekday, the ranges of all of them merged (overlapping or
     * touching ranges become one). A day nobody works stays closed. This is what a customer can be offered.
     */
    public static WeeklyHours union(List<WeeklyHours> all) {
        Map<DayOfWeek, List<TimeRange>> merged = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            List<TimeRange> ranges = new ArrayList<>();
            for (WeeklyHours h : all) {
                ranges.addAll(h.rangesFor(day));
            }
            ranges.sort(Comparator.comparing(TimeRange::start));
            List<TimeRange> out = new ArrayList<>();
            for (TimeRange r : ranges) {
                if (!out.isEmpty() && !r.start().isAfter(out.getLast().end())) {
                    TimeRange last = out.removeLast();
                    out.add(new TimeRange(last.start(), r.end().isAfter(last.end()) ? r.end() : last.end()));
                } else {
                    out.add(r);
                }
            }
            if (!out.isEmpty()) {
                merged.put(day, out);
            }
        }
        return new WeeklyHours(merged);
    }

    public boolean isOpen(DayOfWeek day) {
        return !rangesFor(day).isEmpty();
    }
}
