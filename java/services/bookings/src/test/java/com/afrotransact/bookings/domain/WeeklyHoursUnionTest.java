package com.afrotransact.bookings.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WeeklyHoursUnionTest {

    private static WeeklyHours hours(DayOfWeek day, String start, String end) {
        return new WeeklyHours(Map.of(day, List.of(TimeRange.of(start, end))));
    }

    @Test
    void nobodyWorkingMeansClosed() {
        assertThat(WeeklyHours.union(List.of()).isOpen(DayOfWeek.MONDAY)).isFalse();
        assertThat(WeeklyHours.union(List.of(WeeklyHours.closed())).isOpen(DayOfWeek.MONDAY)).isFalse();
    }

    @Test
    void overlappingAndTouchingRangesMergeAndSeparateOnesStay() {
        WeeklyHours a = hours(DayOfWeek.TUESDAY, "09:00", "12:00");
        WeeklyHours b = hours(DayOfWeek.TUESDAY, "11:00", "14:00");
        WeeklyHours c = hours(DayOfWeek.TUESDAY, "14:00", "16:00");
        WeeklyHours d = hours(DayOfWeek.TUESDAY, "18:00", "20:00");
        assertThat(WeeklyHours.union(List.of(a, b, c, d)).rangesFor(DayOfWeek.TUESDAY)).containsExactly(
                TimeRange.of("09:00", "16:00"), TimeRange.of("18:00", "20:00"));
    }

    @Test
    void aDayOnlyOneMemberWorksIsOpenAndOthersStayClosed() {
        WeeklyHours u = WeeklyHours.union(List.of(hours(DayOfWeek.SATURDAY, "10:00", "13:00"), WeeklyHours.closed()));
        assertThat(u.isOpen(DayOfWeek.SATURDAY)).isTrue();
        assertThat(u.isOpen(DayOfWeek.SUNDAY)).isFalse();
    }
}
