package com.afrotransact.bookings.adapter.in.web;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.bookings.adapter.in.web.BookingDtos.BookingResponse;
import com.afrotransact.bookings.adapter.in.web.BookingDtos.EarningsResponse;
import com.afrotransact.bookings.adapter.in.web.ProviderDtos.AssignMembersRequest;
import com.afrotransact.bookings.adapter.in.web.ProviderDtos.CalendarResponse;
import com.afrotransact.bookings.adapter.in.web.ProviderDtos.MemberRequest;
import com.afrotransact.bookings.adapter.in.web.ProviderDtos.MemberResponse;
import com.afrotransact.bookings.adapter.in.web.ProviderDtos.OverrideDto;
import com.afrotransact.bookings.adapter.in.web.ProviderDtos.PolicyRequest;
import com.afrotransact.bookings.adapter.in.web.ProviderDtos.ProviderResponse;
import com.afrotransact.bookings.adapter.in.web.ProviderDtos.ServiceRequest;
import com.afrotransact.bookings.adapter.in.web.ProviderDtos.ServiceResponse;
import com.afrotransact.bookings.adapter.in.web.ProviderDtos.SettingsRequest;
import com.afrotransact.bookings.adapter.in.web.ProviderDtos.WeeklyHoursDto;
import com.afrotransact.bookings.application.AvailabilityService;
import com.afrotransact.bookings.application.BookingService;
import com.afrotransact.bookings.application.ProviderSetupService;
import com.afrotransact.bookings.application.ProviderSetupService.SettingsCommand;
import com.afrotransact.bookings.application.ServiceCatalogService;
import com.afrotransact.bookings.application.ServiceView;
import com.afrotransact.bookings.application.TeamService;
import com.afrotransact.bookings.domain.BookingStatus;
import com.afrotransact.bookings.domain.TeamMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Provider Hub", description = "Setup, cancellation policy, services & prices, team, working hours, "
        + "calendar, bookings and earnings of a provider organisation. Roles: Owner and Manager full; Practitioner "
        + "own calendar and hours only; Finance view only (enforced server-side).")
@RestController
@RequestMapping("/v1/providers/{orgId}")
class ProviderController {

    private final ProviderSetupService setup;
    private final ServiceCatalogService services;
    private final TeamService team;
    private final AvailabilityService availability;
    private final BookingService bookings;

    ProviderController(ProviderSetupService setup, ServiceCatalogService services, TeamService team,
                       AvailabilityService availability, BookingService bookings) {
        this.setup = setup;
        this.services = services;
        this.team = team;
        this.availability = availability;
        this.bookings = bookings;
    }

    // ------------------------------------------------------------------ setup

    @Operation(summary = "Booking setup (bookings:read)")
    @GetMapping("/settings")
    ProviderResponse settings(@PathVariable String orgId) {
        return ProviderResponse.of(setup.get(orgId));
    }

    @Operation(summary = "Create or update the setup: name, time zone, rules (bookings:availability:write; not "
            + "practitioners)", description = "Minimum notice, booking horizon, buffer, slot interval, daily limit, "
            + "instant booking and auto-accepting reschedules. Customers see the change immediately.")
    @PutMapping("/settings")
    ProviderResponse saveSettings(@PathVariable String orgId, @Valid @RequestBody SettingsRequest body) {
        return ProviderResponse.of(setup.saveSettings(orgId, new SettingsCommand(body.displayName(),
                ProviderDtos.zone(body.timeZone()), body.currency(), body.location(),
                body.rules() == null ? null : body.rules().toDomain())));
    }

    @Operation(summary = "Set the cancellation policy: FLEXIBLE, MODERATE, STRICT or CUSTOM "
            + "(bookings:services:write)", description = "Existing bookings keep the policy they were made under.")
    @PutMapping("/cancellation-policy")
    ProviderResponse savePolicy(@PathVariable String orgId, @Valid @RequestBody PolicyRequest body) {
        return ProviderResponse.of(setup.savePolicy(orgId, body.kind(),
                body.custom() == null ? null : body.custom().toDomain()));
    }

    // ------------------------------------------------------------------ services & prices

    @Operation(summary = "Services & prices with the provider's take-home (bookings:read)")
    @GetMapping("/services")
    List<ServiceResponse> services(@PathVariable String orgId) {
        return services.list(orgId).stream().map(ServiceResponse::of).toList();
    }

    @Operation(summary = "A service with its take-home (bookings:read)")
    @GetMapping("/services/{serviceId}")
    ServiceResponse service(@PathVariable String orgId, @PathVariable UUID serviceId) {
        return ServiceResponse.of(services.get(orgId, serviceId));
    }

    @Operation(summary = "Add a service (bookings:services:write)")
    @PostMapping("/services")
    ResponseEntity<ServiceResponse> createService(@PathVariable String orgId,
                                                  @Valid @RequestBody ServiceRequest body) {
        ServiceView v = services.create(orgId, body.toCommand());
        return ResponseEntity.created(URI.create("/v1/providers/" + orgId + "/services/" + v.offering().id()))
                .body(ServiceResponse.of(v));
    }

    @Operation(summary = "Change a service (bookings:services:write)", description = "Price changes don't affect "
            + "existing bookings.")
    @PutMapping("/services/{serviceId}")
    ServiceResponse updateService(@PathVariable String orgId, @PathVariable UUID serviceId,
                                  @Valid @RequestBody ServiceRequest body) {
        return ServiceResponse.of(services.update(orgId, serviceId, body.toCommand()));
    }

    @Operation(summary = "Stop offering a service (bookings:services:write)")
    @PostMapping("/services/{serviceId}/deactivate")
    ServiceResponse deactivate(@PathVariable String orgId, @PathVariable UUID serviceId) {
        return ServiceResponse.of(services.setActive(orgId, serviceId, false));
    }

    @Operation(summary = "Offer a service again (bookings:services:write)")
    @PostMapping("/services/{serviceId}/activate")
    ServiceResponse activate(@PathVariable String orgId, @PathVariable UUID serviceId) {
        return ServiceResponse.of(services.setActive(orgId, serviceId, true));
    }

    @Operation(summary = "Who performs a service (bookings:team:manage); empty = every bookable member")
    @PutMapping("/services/{serviceId}/members")
    ServiceResponse assign(@PathVariable String orgId, @PathVariable UUID serviceId,
                           @Valid @RequestBody AssignMembersRequest body) {
        return ServiceResponse.of(services.assignMembers(orgId, serviceId, body.memberIds()));
    }

    // ------------------------------------------------------------------ team

    @Operation(summary = "Team members with their roles and hours (bookings:read)")
    @GetMapping("/team")
    List<MemberResponse> team(@PathVariable String orgId) {
        return team.list(orgId).stream().map(MemberResponse::of).toList();
    }

    @Operation(summary = "Add a team member: Owner, Manager, Practitioner or Finance (bookings:team:manage)")
    @PostMapping("/team")
    ResponseEntity<MemberResponse> addMember(@PathVariable String orgId, @Valid @RequestBody MemberRequest body) {
        TeamMember m = team.add(orgId, body.toCommand());
        return ResponseEntity.created(URI.create("/v1/providers/" + orgId + "/team/" + m.id()))
                .body(MemberResponse.of(m));
    }

    @Operation(summary = "Change a member's role, login link, bookability or status (bookings:team:manage)")
    @PutMapping("/team/{memberId}")
    MemberResponse updateMember(@PathVariable String orgId, @PathVariable UUID memberId,
                                @Valid @RequestBody MemberRequest body) {
        return MemberResponse.of(team.update(orgId, memberId, body.toCommand()));
    }

    // ------------------------------------------------------------------ availability

    @Operation(summary = "Set weekly working hours with breaks (bookings:availability:write; practitioners: own)")
    @PutMapping("/team/{memberId}/hours")
    MemberResponse setHours(@PathVariable String orgId, @PathVariable UUID memberId,
                            @Valid @RequestBody WeeklyHoursDto body) {
        return MemberResponse.of(availability.setHours(orgId, memberId, body.toDomain()));
    }

    @Operation(summary = "Time off and special hours in a date range (bookings:read; practitioners: own)")
    @GetMapping("/team/{memberId}/overrides")
    List<OverrideDto> overrides(@PathVariable String orgId, @PathVariable UUID memberId,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return availability.overrides(orgId, memberId, from, to).stream().map(OverrideDto::of).toList();
    }

    @Operation(summary = "Block a date (time off) or set special hours (bookings:availability:write; "
            + "practitioners: own)")
    @PutMapping("/team/{memberId}/overrides")
    OverrideDto putOverride(@PathVariable String orgId, @PathVariable UUID memberId,
                            @Valid @RequestBody OverrideDto body) {
        return OverrideDto.of(availability.putOverride(orgId, memberId, body.toDomain()));
    }

    @Operation(summary = "Remove time off / special hours of a date (bookings:availability:write)")
    @DeleteMapping("/team/{memberId}/overrides/{date}")
    ResponseEntity<Void> removeOverride(@PathVariable String orgId, @PathVariable UUID memberId,
                                        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        availability.removeOverride(orgId, memberId, date);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ calendar, bookings, earnings

    @Operation(summary = "Week / month calendar (bookings:read; practitioners: own column)",
            description = "Open hours and time off per member and day, what's on each day (appointments, hours "
                    + "booked, expected revenue, requests waiting) and the bookings for the quick view.")
    @GetMapping("/calendar")
    CalendarResponse calendar(@PathVariable String orgId,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                              @RequestParam(required = false) UUID memberId) {
        return CalendarResponse.of(availability.calendar(orgId, from, to, memberId));
    }

    @Operation(summary = "Bookings starting in [from, to) (bookings:read; practitioners: own)")
    @GetMapping("/bookings")
    List<BookingResponse> bookings(@PathVariable String orgId, @RequestParam Instant from, @RequestParam Instant to,
                                   @RequestParam(required = false) UUID memberId,
                                   @RequestParam(required = false) BookingStatus status) {
        return bookings.forProvider(orgId, from, to, memberId, status).stream().map(BookingResponse::of).toList();
    }

    @Operation(summary = "Earnings: collected − connection fee − card fees − late-cancellation fees (bookings:read)",
            description = "Commission is taken only on what the customer actually paid (after refunds).")
    @GetMapping("/earnings")
    EarningsResponse earnings(@PathVariable String orgId, @RequestParam Instant from, @RequestParam Instant to) {
        return EarningsResponse.of(bookings.earnings(orgId, from, to));
    }
}
