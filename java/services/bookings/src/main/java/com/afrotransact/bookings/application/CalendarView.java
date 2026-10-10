package com.afrotransact.bookings.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.TimeRange;

/**
 * Week / month calendar of a provider: open hours per member and day (with time off) and the bookings in range.
 *
 * @param days one entry per member and date
 */
public record CalendarView(String orgId, ZoneId timeZone, LocalDate from, LocalDate to, List<Day> days,
                           List<Booking> bookings) {

    /**
     * One member's day: open hours and what's on (the day hover in the design).
     *
     * @param awaitingAction requests or new-time requests waiting on the provider
     */
    public record Day(UUID memberId, String memberName, LocalDate date, List<TimeRange> open, boolean timeOff,
                      String note, int appointments, long bookedMinutes, long expectedRevenueMinor,
                      int awaitingAction) {
    }
}
