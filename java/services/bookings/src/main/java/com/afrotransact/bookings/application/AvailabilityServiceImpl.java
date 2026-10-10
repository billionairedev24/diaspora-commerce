package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.BookingStatus;
import com.afrotransact.bookings.domain.BookingsRuleViolation;
import com.afrotransact.bookings.domain.DayOverride;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.TeamMember;
import com.afrotransact.bookings.domain.TimeRange;
import com.afrotransact.bookings.domain.WeeklyHours;
import com.afrotransact.bookings.port.BookingRepository;
import com.afrotransact.bookings.port.ProviderRepository;
import com.afrotransact.bookings.port.TeamRepository;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AvailabilityServiceImpl implements AvailabilityService {

    private static final int MAX_CALENDAR_DAYS = 62;

    private final TeamRepository team;
    private final ProviderRepository providers;
    private final BookingRepository bookings;
    private final BookingsAccess access;
    private final Clock clock;

    AvailabilityServiceImpl(TeamRepository team, ProviderRepository providers, BookingRepository bookings,
                            BookingsAccess access, Clock clock) {
        this.team = team;
        this.providers = providers;
        this.bookings = bookings;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TeamMember setHours(String orgId, UUID memberId, WeeklyHours hours) {
        ProviderScope scope = access.provider(orgId, BookingsPermissions.AVAILABILITY_WRITE);
        TeamMember m = team.lock(memberId).orElseThrow(() -> memberNotFound(memberId));
        access.requireMember(scope, m);
        return team.update(m.withHours(hours, Instant.now(clock)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DayOverride> overrides(String orgId, UUID memberId, LocalDate from, LocalDate to) {
        ProviderScope scope = access.provider(orgId, BookingsPermissions.READ);
        access.requireMember(scope, member(memberId));
        return team.overrides(memberId, from, to);
    }

    @Override
    @Transactional
    public DayOverride putOverride(String orgId, UUID memberId, DayOverride override) {
        ProviderScope scope = access.provider(orgId, BookingsPermissions.AVAILABILITY_WRITE);
        access.requireMember(scope, team.lock(memberId).orElseThrow(() -> memberNotFound(memberId)));
        team.putOverride(memberId, override);
        return override;
    }

    @Override
    @Transactional
    public void removeOverride(String orgId, UUID memberId, LocalDate date) {
        ProviderScope scope = access.provider(orgId, BookingsPermissions.AVAILABILITY_WRITE);
        access.requireMember(scope, member(memberId));
        if (!team.deleteOverride(memberId, date)) {
            throw new NotFoundException("bookings.override-not-found", "No time off or special hours on " + date);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CalendarView calendar(String orgId, LocalDate from, LocalDate to, UUID memberId) {
        ProviderScope scope = access.provider(orgId, BookingsPermissions.READ);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_CALENDAR_DAYS) {
            throw new BookingsRuleViolation("bookings.invalid-range", "Calendar range must be 1–" + MAX_CALENDAR_DAYS
                    + " days");
        }
        Provider p = providers.find(orgId).orElseThrow(() -> ProviderSetupServiceImpl.notFound(orgId));
        ZoneId zone = p.timeZone();
        List<TeamMember> members = team.byOrg(orgId).stream()
                .filter(m -> memberId == null || m.id().equals(memberId))
                .filter(m -> !scope.ownOnly() || m.isUser(scope.actor().subject()))
                .toList();
        if (memberId != null && members.isEmpty()) {
            if (scope.ownOnly()) {
                throw new ForbiddenActionException("bookings.own-calendar-only",
                        "Practitioners only see their own calendar");
            }
            throw memberNotFound(memberId);
        }
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        Map<UUID, TeamMember> byId = members.stream().collect(Collectors.toMap(TeamMember::id, Function.identity()));
        List<Booking> inRange = bookings.forProvider(orgId, start, end, null, null).stream()
                .filter(b -> byId.containsKey(b.memberId()))
                .toList();
        List<CalendarView.Day> days = new ArrayList<>();
        for (TeamMember m : members) {
            Map<LocalDate, DayOverride> overrides = team.overrides(m.id(), from, to).stream()
                    .collect(Collectors.toMap(DayOverride::date, Function.identity()));
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                DayOverride o = overrides.get(d);
                List<TimeRange> open = o == null ? m.hours().rangesFor(d.getDayOfWeek()) : o.ranges();
                LocalDate day = d;
                List<Booking> mine = inRange.stream()
                        .filter(b -> b.memberId().equals(m.id()))
                        .filter(b -> b.startAt().atZone(zone).toLocalDate().equals(day))
                        .toList();
                List<Booking> live = mine.stream().filter(b -> b.status().isActive()
                        || b.status() == BookingStatus.COMPLETED).toList();
                long minutes = live.stream().mapToLong(b -> b.service().durationMinutes()).sum();
                long revenue = live.stream().mapToLong(b -> b.service().priceMinor()).sum();
                int waiting = (int) mine.stream().filter(b -> b.status() == BookingStatus.REQUESTED
                        || b.status() == BookingStatus.RESCHEDULE_REQUESTED).count();
                days.add(new CalendarView.Day(m.id(), m.displayName(), d, open, o != null && o.closed(),
                        o == null ? null : o.note(), live.size(), minutes, revenue, waiting));
            }
        }
        return new CalendarView(orgId, zone, from, to, days, inRange);
    }

    private TeamMember member(UUID id) {
        return team.find(id).orElseThrow(() -> memberNotFound(id));
    }

    private static NotFoundException memberNotFound(UUID id) {
        return new NotFoundException("bookings.member-not-found", "Team member " + id + " not found");
    }
}
