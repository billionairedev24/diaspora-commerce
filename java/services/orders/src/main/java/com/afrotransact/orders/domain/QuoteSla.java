package com.afrotransact.orders.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** The service level of a quote request in business days (Monday to Friday, in the business time zone). */
public final class QuoteSla {

    /** Where a request stands against its service level. */
    public enum State {
        ON_TIME,
        DUE_SOON,
        OVERDUE
    }

    private QuoteSla() {
    }

    /**
     * When the answer is due: {@code businessDays} working days after {@code start}, at the same time of day. A request
     * that arrives on a weekend starts counting on Monday morning (its clock starts at the first working moment).
     */
    public static Instant dueAt(Instant start, int businessDays, ZoneId zone) {
        ZonedDateTime t = start.atZone(zone);
        while (weekend(t.getDayOfWeek())) {
            t = t.toLocalDate().plusDays(1).atStartOfDay(zone);
        }
        int left = Math.max(1, businessDays);
        LocalDateTime d = t.toLocalDateTime();
        while (left > 0) {
            d = d.plusDays(1);
            if (!weekend(d.getDayOfWeek())) {
                left--;
            }
        }
        return d.atZone(zone).toInstant();
    }

    /** The state at {@code now}: due soon from {@code dueSoonPercent} of the time between start and due. */
    public static State state(Instant start, Instant due, Instant now, int dueSoonPercent) {
        if (!now.isBefore(due)) {
            return State.OVERDUE;
        }
        Duration total = Duration.between(start, due);
        Duration used = Duration.between(start, now);
        return used.toMillis() * 100 >= total.toMillis() * dueSoonPercent ? State.DUE_SOON : State.ON_TIME;
    }

    private static boolean weekend(DayOfWeek d) {
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }
}
