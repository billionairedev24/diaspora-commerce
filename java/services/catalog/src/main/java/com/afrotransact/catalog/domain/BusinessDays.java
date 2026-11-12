package com.afrotransact.catalog.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** Business-day arithmetic for review deadlines: Monday to Friday in UTC (no holiday calendar). */
public final class BusinessDays {

    private BusinessDays() {
    }

    /** The instant {@code days} business days after {@code from}, keeping the time of day. */
    public static Instant add(Instant from, int days) {
        ZonedDateTime t = from.atZone(ZoneOffset.UTC);
        int left = days;
        while (left > 0) {
            t = t.plusDays(1);
            if (isBusinessDay(t.getDayOfWeek())) {
                left--;
            }
        }
        return t.toInstant();
    }

    /** Whole hours between two instants (never negative). */
    public static long hoursBetween(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toHours());
    }

    private static boolean isBusinessDay(DayOfWeek day) {
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
