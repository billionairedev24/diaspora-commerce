package com.afrotransact.bookings.adapter.in.web;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.bookings.application.CalendarView;
import com.afrotransact.bookings.application.ServiceCatalogService.ServiceCommand;
import com.afrotransact.bookings.application.ServiceView;
import com.afrotransact.bookings.application.TeamService.MemberCommand;
import com.afrotransact.bookings.domain.AddOn;
import com.afrotransact.bookings.domain.BookingRules;
import com.afrotransact.bookings.domain.CancellationPolicy;
import com.afrotransact.bookings.domain.CustomPolicySettings;
import com.afrotransact.bookings.domain.DayOverride;
import com.afrotransact.bookings.domain.NoShowRule;
import com.afrotransact.bookings.domain.PolicyKind;
import com.afrotransact.bookings.domain.Provider;
import com.afrotransact.bookings.domain.RefundTier;
import com.afrotransact.bookings.domain.ServiceMode;
import com.afrotransact.bookings.domain.ServiceOffering;
import com.afrotransact.bookings.domain.TakeHome;
import com.afrotransact.bookings.domain.TeamMember;
import com.afrotransact.bookings.domain.TeamRole;
import com.afrotransact.bookings.domain.TimeRange;
import com.afrotransact.bookings.domain.WeeklyHours;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies of Provider Hub endpoints: setup, policy, services, team, hours, calendar. */
final class ProviderDtos {

    static final String HH_MM = "^([01][0-9]|2[0-3]):[0-5][0-9]$";

    private ProviderDtos() {
    }

    // ------------------------------------------------------------------ setup & rules

    @Schema(name = "BookingRules", description = "Working-hours screen rules")
    record RulesDto(@Schema(description = "Minimum notice in minutes", example = "720") @Min(0) int leadMinutes,
                    @Schema(description = "Book up to N days ahead", example = "30") @Min(1) @Max(365)
                    int horizonDays,
                    @Schema(description = "Buffer after each booking", example = "15") @Min(0) @Max(240)
                    int bufferMinutes,
                    @Schema(description = "Time slot interval", example = "30") @Min(5) @Max(240) int stepMinutes,
                    @Schema(description = "Max bookings per team member per day", example = "6") @Min(1) @Max(50)
                    int maxPerDay,
                    boolean instantBooking,
                    boolean autoAcceptReschedule) {

        BookingRules toDomain() {
            return new BookingRules(leadMinutes, horizonDays, bufferMinutes, stepMinutes, maxPerDay, instantBooking,
                    autoAcceptReschedule);
        }

        static RulesDto of(BookingRules r) {
            return new RulesDto(r.leadMinutes(), r.horizonDays(), r.bufferMinutes(), r.stepMinutes(), r.maxPerDay(),
                    r.instantBooking(), r.autoAcceptReschedule());
        }
    }

    @Schema(name = "ProviderSettingsRequest")
    record SettingsRequest(@NotBlank @Size(max = 120) String displayName,
                           @NotBlank @Schema(example = "America/Chicago") String timeZone,
                           @Pattern(regexp = "^[A-Za-z]{3}$") @Schema(example = "USD") String currency,
                           @Size(max = 200) String location,
                           @Valid RulesDto rules) {
    }

    @Schema(name = "CustomPolicy")
    record CustomPolicyDto(@Min(0) @Max(720) int freeHours, boolean partialRefund, @Min(0) int partialFromHours,
                           @Min(0) @Max(99) int partialPercent, @NotNull NoShowRule noShow,
                           @Min(0) @Max(720) int rescheduleHours, @Min(0) @Max(10) int maxReschedules) {

        CustomPolicySettings toDomain() {
            return new CustomPolicySettings(freeHours, partialRefund, partialFromHours, partialPercent, noShow,
                    rescheduleHours, maxReschedules);
        }
    }

    @Schema(name = "CancellationPolicyRequest")
    record PolicyRequest(@NotNull PolicyKind kind, @Valid CustomPolicyDto custom) {
    }

    @Schema(name = "CancellationPolicy")
    record PolicyResponse(String kind, String label, String text, int freeHours, List<RefundTier> tiers,
                          String noShow, int rescheduleHours, int maxReschedules) {

        static PolicyResponse of(CancellationPolicy p) {
            return new PolicyResponse(p.kind().name(), p.label(), p.text(), p.freeHours(), p.tiers(),
                    p.noShow().name(), p.rescheduleHours(), p.maxReschedules());
        }
    }

    @Schema(name = "Provider")
    record ProviderResponse(String orgId, String displayName, String timeZone, String currency, String location,
                            RulesDto rules, PolicyResponse policy, int strikes, Instant updatedAt, long version) {

        static ProviderResponse of(Provider p) {
            return new ProviderResponse(p.orgId(), p.displayName(), p.timeZone().getId(), p.currency(), p.location(),
                    RulesDto.of(p.rules()), PolicyResponse.of(p.policy()), p.strikes(), p.updatedAt(), p.version());
        }
    }

    // ------------------------------------------------------------------ services & prices

    @Schema(name = "AddOn")
    record AddOnDto(@NotBlank @Size(max = 40) String id, @NotBlank @Size(max = 80) String name,
                    @PositiveOrZero long priceMinor, @Min(0) @Max(480) int extraMinutes) {

        AddOn toDomain() {
            return new AddOn(id, name, priceMinor, extraMinutes);
        }

        static AddOnDto of(AddOn a) {
            return new AddOnDto(a.id(), a.name(), a.priceMinor(), a.extraMinutes());
        }
    }

    @Schema(name = "ServiceRequest", description = "Services & prices form")
    record ServiceRequest(@NotBlank @Size(min = 3, max = 120) String name,
                          @Size(max = 2000) String description,
                          @Size(max = 60) @Schema(example = "hair & beauty") String category,
                          @Min(5) @Max(1440) int durationMinutes,
                          @Positive long priceMinor,
                          @PositiveOrZero long depositMinor,
                          @NotEmpty Set<ServiceMode> modes,
                          @Size(max = 20) List<@Valid AddOnDto> addOns) {

        ServiceCommand toCommand() {
            return new ServiceCommand(name, description, category, durationMinutes, priceMinor, depositMinor, modes,
                    addOns == null ? List.of() : addOns.stream().map(AddOnDto::toDomain).toList());
        }
    }

    @Schema(name = "TakeHome", description = "What the provider receives at the listed price")
    record TakeHomeDto(long priceMinor, long depositMinor, long balanceMinor, int commissionBps, long commissionMinor,
                       long cardFeesMinor, long takeHomeMinor, String currency,
                       @Schema(description = "Where the commission comes from: payments' OVERRIDE, CATEGORY, DEFAULT, "
                               + "RETAIL, or CONFIG (configured fallback while payments is unavailable)")
                       String feeSource,
                       @Schema(description = "payments' fee-schedule version (null for the fallback)")
                       Integer feeVersion) {

        static TakeHomeDto of(TakeHome t) {
            return new TakeHomeDto(t.priceMinor(), t.depositMinor(), t.balanceMinor(), t.commissionBps(),
                    t.commissionMinor(), t.cardFeesMinor(), t.takeHomeMinor(), t.currency(), t.feeSource(),
                    t.feeVersion());
        }
    }

    @Schema(name = "Service")
    record ServiceResponse(UUID id, String orgId, String name, String description, String category,
                           int durationMinutes, long priceMinor, long depositMinor, long balanceMinor, String currency,
                           Set<ServiceMode> modes, List<AddOnDto> addOns, Set<UUID> memberIds, boolean active,
                           TakeHomeDto takeHome, Instant updatedAt, long version) {

        static ServiceResponse of(ServiceView v) {
            ServiceOffering o = v.offering();
            return new ServiceResponse(o.id(), o.orgId(), o.name(), o.description(), o.category(),
                    o.durationMinutes(), o.priceMinor(), o.depositMinor(), o.balanceMinor(), o.currency(), o.modes(),
                    o.addOns().stream().map(AddOnDto::of).toList(), o.memberIds(), o.active(),
                    v.takeHome() == null ? null : TakeHomeDto.of(v.takeHome()), o.updatedAt(), o.version());
        }
    }

    @Schema(name = "AssignMembersRequest")
    record AssignMembersRequest(@NotNull Set<UUID> memberIds) {
    }

    // ------------------------------------------------------------------ team & hours

    @Schema(name = "TimeRange", description = "Local wall-clock range, end exclusive")
    record TimeRangeDto(@NotNull @Pattern(regexp = HH_MM) @Schema(example = "10:00") String start,
                        @NotNull @Pattern(regexp = HH_MM) @Schema(example = "18:00") String end) {

        TimeRange toDomain() {
            return TimeRange.of(start, end);
        }

        static TimeRangeDto of(TimeRange r) {
            return new TimeRangeDto(r.start().toString(), r.end().toString());
        }
    }

    @Schema(name = "WeeklyHours", description = "Ranges per weekday (MONDAY…SUNDAY); several ranges = breaks; "
            + "missing day = closed")
    record WeeklyHoursDto(@NotNull Map<DayOfWeek, List<@Valid TimeRangeDto>> days) {

        WeeklyHours toDomain() {
            Map<DayOfWeek, List<TimeRange>> out = new EnumMap<>(DayOfWeek.class);
            days.forEach((d, ranges) -> out.put(d, ranges == null ? List.of()
                    : ranges.stream().map(TimeRangeDto::toDomain).toList()));
            return new WeeklyHours(out);
        }

        static WeeklyHoursDto of(WeeklyHours h) {
            Map<DayOfWeek, List<TimeRangeDto>> out = new EnumMap<>(DayOfWeek.class);
            h.days().forEach((d, ranges) -> out.put(d, ranges.stream().map(TimeRangeDto::of).toList()));
            return new WeeklyHoursDto(out);
        }
    }

    @Schema(name = "TeamMemberRequest")
    record MemberRequest(@Size(max = 120) @Schema(description = "Identity user id of the member's login") String userId,
                         @NotBlank @Size(max = 120) String displayName,
                         @NotNull TeamRole role,
                         @Schema(description = "Takes appointments; default: owners and practitioners") Boolean bookable,
                         Boolean active,
                         @Valid WeeklyHoursDto hours) {

        MemberCommand toCommand() {
            return new MemberCommand(userId, displayName, role, bookable, active,
                    hours == null ? null : hours.toDomain());
        }
    }

    @Schema(name = "TeamMember")
    record MemberResponse(UUID id, String orgId, String userId, String displayName, String role, boolean bookable,
                          boolean active, WeeklyHoursDto hours, long version) {

        static MemberResponse of(TeamMember m) {
            return new MemberResponse(m.id(), m.orgId(), m.userId(), m.displayName(), m.role().name(), m.bookable(),
                    m.active(), WeeklyHoursDto.of(m.hours()), m.version());
        }
    }

    @Schema(name = "DayOverride", description = "Time off (closed) or special hours on one date")
    record OverrideDto(@NotNull LocalDate date, boolean closed, List<@Valid TimeRangeDto> ranges,
                       @Size(max = 200) String note) {

        DayOverride toDomain() {
            List<TimeRange> out = new ArrayList<>();
            if (ranges != null) {
                ranges.forEach(r -> out.add(r.toDomain()));
            }
            return new DayOverride(date, closed, out, note);
        }

        static OverrideDto of(DayOverride o) {
            return new OverrideDto(o.date(), o.closed(), o.ranges().stream().map(TimeRangeDto::of).toList(),
                    o.note());
        }
    }

    // ------------------------------------------------------------------ calendar

    @Schema(name = "CalendarDay")
    record CalendarDayDto(UUID memberId, String memberName, LocalDate date, List<TimeRangeDto> open, boolean timeOff,
                          String note, int appointments, long bookedMinutes, long expectedRevenueMinor,
                          int awaitingAction) {

        static CalendarDayDto of(CalendarView.Day d) {
            return new CalendarDayDto(d.memberId(), d.memberName(), d.date(),
                    d.open().stream().map(TimeRangeDto::of).toList(), d.timeOff(), d.note(), d.appointments(),
                    d.bookedMinutes(), d.expectedRevenueMinor(), d.awaitingAction());
        }
    }

    @Schema(name = "Calendar")
    record CalendarResponse(String orgId, String timeZone, LocalDate from, LocalDate to, List<CalendarDayDto> days,
                            List<BookingDtos.BookingResponse> bookings) {

        static CalendarResponse of(CalendarView v) {
            return new CalendarResponse(v.orgId(), v.timeZone().getId(), v.from(), v.to(),
                    v.days().stream().map(CalendarDayDto::of).toList(),
                    v.bookings().stream().map(BookingDtos.BookingResponse::of).toList());
        }
    }

    static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unknown time zone " + id);
        }
    }
}
