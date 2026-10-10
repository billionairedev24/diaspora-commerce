package com.afrotransact.bookings.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provider-wide booking rules from the "Working hours" screen.
 *
 * @param leadMinutes          minimum notice: latest a customer can book before the start time
 * @param horizonDays          how far ahead customers can book
 * @param bufferMinutes        clean-up or travel time after each appointment
 * @param stepMinutes          how often start times are offered
 * @param maxPerDay            maximum bookings per team member per day
 * @param instantBooking       requests are confirmed automatically and the deposit captured straight away
 * @param autoAcceptReschedule customer moves inside the free-change window to an open slot are accepted automatically
 */
public record BookingRules(int leadMinutes, int horizonDays, int bufferMinutes, int stepMinutes, int maxPerDay,
                           boolean instantBooking, boolean autoAcceptReschedule) {

    public BookingRules {
        Map<String, String> errors = new LinkedHashMap<>();
        if (leadMinutes < 0 || leadMinutes > 30 * 24 * 60) {
            errors.put("leadMinutes", "Between 0 and 30 days");
        }
        if (horizonDays < 1 || horizonDays > 365) {
            errors.put("horizonDays", "Between 1 and 365 days");
        }
        if (bufferMinutes < 0 || bufferMinutes > 240) {
            errors.put("bufferMinutes", "Between 0 and 240 minutes");
        }
        if (stepMinutes < 5 || stepMinutes > 240) {
            errors.put("stepMinutes", "Between 5 and 240 minutes");
        }
        if (maxPerDay < 1 || maxPerDay > 50) {
            errors.put("maxPerDay", "Between 1 and 50");
        }
        if (!errors.isEmpty()) {
            throw new BookingsRuleViolation("bookings.invalid-rules", "Booking rules are invalid", errors);
        }
    }

    /** Defaults of a new provider (Provider Hub): 12 h notice, 30 days ahead, 15 min buffer, every 30 min, 6/day. */
    public static BookingRules defaults() {
        return new BookingRules(12 * 60, 30, 15, 30, 6, false, true);
    }
}
