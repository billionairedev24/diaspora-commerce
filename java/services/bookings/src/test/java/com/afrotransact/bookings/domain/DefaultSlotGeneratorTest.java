package com.afrotransact.bookings.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class DefaultSlotGeneratorTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 15);
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final WeeklyHours HOURS = new WeeklyHours(Map.of(
            DayOfWeek.THURSDAY, List.of(TimeRange.of("10:00", "14:00"), TimeRange.of("14:30", "20:00")),
            DayOfWeek.MONDAY, List.of()));

    private final SlotGenerator generator = new DefaultSlotGenerator();

    private static BookingRules rules(int lead, int buffer, int step, int maxPerDay, int horizon) {
        return new BookingRules(lead, horizon, buffer, step, maxPerDay, false, true);
    }

    private static List<LocalTime> localStarts(List<Slot> slots) {
        return slots.stream().map(s -> s.start().atZone(CHICAGO).toLocalTime()).toList();
    }

    private static Instant at(String localTime) {
        return THURSDAY.atTime(LocalTime.parse(localTime)).atZone(CHICAGO).toInstant();
    }

    @Test
    void startTimesEveryStepInsideEachRangeAndNeverAcrossTheBreak() {
        List<Slot> slots = generator.slots(new SlotQuery(THURSDAY, CHICAGO, 120, rules(0, 0, 30, 6, 30), HOURS,
                Optional.empty(), List.of(), 0, NOW));
        assertThat(localStarts(slots)).containsExactly(
                LocalTime.of(10, 0), LocalTime.of(10, 30), LocalTime.of(11, 0), LocalTime.of(11, 30),
                LocalTime.of(12, 0),
                LocalTime.of(14, 30), LocalTime.of(15, 0), LocalTime.of(15, 30), LocalTime.of(16, 0),
                LocalTime.of(16, 30), LocalTime.of(17, 0), LocalTime.of(17, 30), LocalTime.of(18, 0));
        assertThat(slots.getFirst().end()).isEqualTo(at("12:00"));
    }

    @Test
    void closedWeekdaysHaveNoSlots() {
        assertThat(generator.slots(new SlotQuery(LocalDate.of(2026, 10, 12), CHICAGO, 60, rules(0, 0, 30, 6, 30),
                HOURS, Optional.empty(), List.of(), 0, NOW))).isEmpty();
    }

    @Test
    void minimumNoticeHidesStartsThatAreTooSoon() {
        Instant now = at("11:10");
        List<Slot> slots = generator.slots(new SlotQuery(THURSDAY, CHICAGO, 60, rules(60, 0, 30, 6, 30), HOURS,
                Optional.empty(), List.of(), 0, now));
        assertThat(localStarts(slots).getFirst()).isEqualTo(LocalTime.of(12, 30));
    }

    @Test
    void bufferKeepsTimeFreeOnBothSidesOfAnExistingAppointment() {
        BusyInterval existing = new BusyInterval(at("15:00"), at("17:30"));
        List<LocalTime> starts = localStarts(generator.slots(new SlotQuery(THURSDAY, CHICAGO, 60,
                rules(0, 30, 30, 6, 30), HOURS, Optional.empty(), List.of(existing), 1, NOW)));
        assertThat(starts).contains(LocalTime.of(13, 0), LocalTime.of(17, 30))
                .doesNotContain(LocalTime.of(13, 30), LocalTime.of(14, 30), LocalTime.of(15, 0),
                        LocalTime.of(17, 0));
    }

    @Test
    void dailyLimitClosesTheDay() {
        assertThat(generator.slots(new SlotQuery(THURSDAY, CHICAGO, 60, rules(0, 0, 30, 2, 30), HOURS,
                Optional.empty(), List.of(), 2, NOW))).isEmpty();
    }

    @Test
    void timeOffAndSpecialHoursOverrideTheWeek() {
        assertThat(generator.slots(new SlotQuery(THURSDAY, CHICAGO, 60, rules(0, 0, 30, 6, 30), HOURS,
                Optional.of(DayOverride.timeOff(THURSDAY, "Vacation")), List.of(), 0, NOW))).isEmpty();
        DayOverride special = new DayOverride(THURSDAY, false, List.of(TimeRange.of("08:00", "09:30")), null);
        assertThat(localStarts(generator.slots(new SlotQuery(THURSDAY, CHICAGO, 60, rules(0, 0, 30, 6, 30), HOURS,
                Optional.of(special), List.of(), 0, NOW))))
                .containsExactly(LocalTime.of(8, 0), LocalTime.of(8, 30));
    }

    @Test
    void nothingBeyondTheBookingHorizon() {
        assertThat(generator.slots(new SlotQuery(THURSDAY, CHICAGO, 60, rules(0, 0, 30, 6, 3), HOURS,
                Optional.empty(), List.of(), 0, NOW))).isEmpty();
    }

    @Test
    void daylightSavingGapStartsAreSkippedAndDurationsAreReal() {
        ZoneId newYork = ZoneId.of("America/New_York");
        LocalDate springForward = LocalDate.of(2026, 3, 8);
        WeeklyHours early = new WeeklyHours(Map.of(DayOfWeek.SUNDAY, List.of(TimeRange.of("00:00", "06:00"))));
        List<Slot> slots = generator.slots(new SlotQuery(springForward, newYork, 60, rules(0, 0, 30, 20, 365), early,
                Optional.empty(), List.of(), 0, Instant.parse("2026-03-01T00:00:00Z")));
        List<LocalTime> starts = slots.stream().map(s -> s.start().atZone(newYork).toLocalTime()).toList();
        assertThat(starts).hasSize(9).doesNotContain(LocalTime.of(2, 0), LocalTime.of(2, 30));
        assertThat(slots).allSatisfy(s -> assertThat(s.end()).isEqualTo(s.start().plusSeconds(3600)));
    }
}
