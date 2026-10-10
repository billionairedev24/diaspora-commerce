package com.afrotransact.bookings.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.bookings.domain.DayOverride;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.domain.Slot;
import com.afrotransact.bookings.domain.SlotGenerator;
import com.afrotransact.bookings.domain.SlotQuery;
import com.afrotransact.bookings.domain.TeamMember;
import com.afrotransact.bookings.port.BookingRepository;
import com.afrotransact.bookings.port.TeamRepository;
import org.springframework.stereotype.Component;

/** Reads hours, overrides and existing appointments, then asks the {@link SlotGenerator} for free start times. */
@Component
class SlotFinder {

    private final TeamRepository team;
    private final BookingRepository bookings;
    private final SlotGenerator generator;

    SlotFinder(TeamRepository team, BookingRepository bookings, SlotGenerator generator) {
        this.team = team;
        this.bookings = bookings;
        this.generator = generator;
    }

    /** A free start time and the team member who would take it. */
    record MemberSlot(TeamMember member, Slot slot) {
    }

    /** Bookable members for the service, optionally only {@code memberId}. */
    List<TeamMember> candidates(ServiceOffering offering, UUID memberId) {
        return team.byOrg(offering.orgId()).stream()
                .filter(TeamMember::takesBookings)
                .filter(m -> offering.performedBy(m.id()))
                .filter(m -> memberId == null || m.id().equals(memberId))
                .toList();
    }

    /** Free start times of every candidate on {@code date}, earliest first (one entry per member and time). */
    List<MemberSlot> slots(Provider provider, ServiceOffering offering, int durationMinutes, LocalDate date,
                           UUID memberId, UUID excludeBookingId, Instant now) {
        List<MemberSlot> out = new ArrayList<>();
        for (TeamMember m : candidates(offering, memberId)) {
            for (Slot s : memberSlots(provider, m, date, durationMinutes, excludeBookingId, now)) {
                out.add(new MemberSlot(m, s));
            }
        }
        out.sort(Comparator.comparing((MemberSlot ms) -> ms.slot().start()));
        return List.copyOf(out);
    }

    /** One member's free start times on a local date of the provider's zone. */
    List<Slot> memberSlots(Provider provider, TeamMember member, LocalDate date, int durationMinutes,
                           UUID excludeBookingId, Instant now) {
        ZoneId zone = provider.timeZone();
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        Optional<DayOverride> override = team.overrides(member.id(), date, date).stream().findFirst();
        return generator.slots(new SlotQuery(date, zone, durationMinutes, provider.rules(), member.hours(), override,
                bookings.busy(member.id(), dayStart.minus(Duration.ofDays(1)), dayEnd.plus(Duration.ofDays(1)),
                        excludeBookingId),
                bookings.countStarting(member.id(), dayStart, dayEnd, excludeBookingId), now));
    }

    /** True when {@code start} is one of the member's free start times. */
    boolean isFree(Provider provider, TeamMember member, Instant start, int durationMinutes, UUID excludeBookingId,
                   Instant now) {
        LocalDate date = start.atZone(provider.timeZone()).toLocalDate();
        return memberSlots(provider, member, date, durationMinutes, excludeBookingId, now).stream()
                .anyMatch(s -> s.start().equals(start));
    }
}
