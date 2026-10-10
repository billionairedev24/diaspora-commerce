package com.afrotransact.bookings.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.bookings.domain.DayOverride;
import com.afrotransact.bookings.domain.TeamMember;
import com.afrotransact.bookings.domain.WeeklyHours;

/**
 * Working hours, time off and the calendar. Practitioners see and change only their own; view-only roles
 * cannot change anything. Changes apply to the slots customers see immediately (slots are computed live).
 */
public interface AvailabilityService {

    TeamMember setHours(String orgId, UUID memberId, WeeklyHours hours);

    List<DayOverride> overrides(String orgId, UUID memberId, LocalDate from, LocalDate to);

    /** Blocks a date (time off) or sets special hours for it. */
    DayOverride putOverride(String orgId, UUID memberId, DayOverride override);

    void removeOverride(String orgId, UUID memberId, LocalDate date);

    /** Week / month view, at most 62 days. Practitioners only get their own column. */
    CalendarView calendar(String orgId, LocalDate from, LocalDate to, UUID memberId);
}
