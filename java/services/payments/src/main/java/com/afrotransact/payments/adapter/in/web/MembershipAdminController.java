package com.afrotransact.payments.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.adapter.in.web.MembershipDtos.BenefitDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.ChargeDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.EventDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.MembershipDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.RefundRequest;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.ReportDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.SettingsChangeDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.SettingsDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.SettingsUpdate;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.TierChangeDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.TierDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.TierOverviewDto;
import com.afrotransact.payments.adapter.in.web.MembershipDtos.TierRequest;
import com.afrotransact.payments.application.MembershipReportService;
import com.afrotransact.payments.application.MembershipService;
import com.afrotransact.payments.application.MembershipSettingsService;
import com.afrotransact.payments.application.MembershipTierService;
import com.afrotransact.payments.application.MembershipTierService.SaveTier;
import com.afrotransact.payments.domain.MembershipStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Membership administration", description = "Staff: define tiers and billing rules in Settings, list "
        + "members, refund a fee, and read churn, benefit cost and recurring revenue (S323). Every change has a "
        + "reason and an audit trail.")
@RestController
class MembershipAdminController {

    private static final int DEFAULT_DAYS = 30;

    private final MembershipTierService tiers;
    private final MembershipSettingsService settings;
    private final MembershipService memberships;
    private final MembershipReportService reports;

    MembershipAdminController(MembershipTierService tiers, MembershipSettingsService settings,
                              MembershipService memberships, MembershipReportService reports) {
        this.tiers = tiers;
        this.settings = settings;
        this.memberships = memberships;
        this.reports = reports;
    }

    // ------------------------------------------------------------------ tiers

    @Operation(operationId = "listMembershipTiersAdmin", summary = "Every tier with its members (payments:read)")
    @GetMapping("/v1/memberships/admin/tiers")
    List<TierOverviewDto> tierOverview() {
        return tiers.overview().stream().map(TierOverviewDto::of).toList();
    }

    @Operation(operationId = "createMembershipTier", summary = "Define a new tier (payments:configure)",
            description = "A reason is required. A tier starts as a draft or is published at once; the benefits come "
                    + "from the menu (free delivery, free shipping, cashback, member prices, early access, priority "
                    + "support, free returns, boosted rewards, monthly credit).")
    @PostMapping("/v1/memberships/admin/tiers")
    @ResponseStatus(HttpStatus.CREATED)
    TierDto createTier(@Valid @RequestBody TierRequest body) {
        return TierDto.of(tiers.create(save(body, null)));
    }

    @Operation(operationId = "updateMembershipTier", summary = "Change a tier, publish it or retire it "
            + "(payments:configure)",
            description = "Needs a reason and the version the editor loaded (409 when someone changed it). A "
                    + "published tier never goes back to draft; retiring stops new sign-ups and keeps current "
                    + "members. Members keep the price they signed up at.")
    @PutMapping("/v1/memberships/admin/tiers/{code}")
    TierDto updateTier(@PathVariable String code, @Valid @RequestBody TierRequest body) {
        return TierDto.of(tiers.update(save(body, code)));
    }

    @Operation(operationId = "listMembershipTierChanges", summary = "The audit history of a tier, newest first "
            + "(payments:read)")
    @GetMapping("/v1/memberships/admin/tiers/{code}/changes")
    List<TierChangeDto> tierChanges(@PathVariable String code, @RequestParam(defaultValue = "50") int limit) {
        return tiers.changes(code, limit).stream().map(TierChangeDto::of).toList();
    }

    private static SaveTier save(TierRequest b, String pathCode) {
        String code = pathCode == null ? b.code() : pathCode;
        return new SaveTier(code, b.name(), b.description(), b.status(), b.sortOrder(), b.monthlyPriceMinor(),
                b.yearlyPriceMinor(), b.trialDays(), b.benefits().stream().map(BenefitDto::toDomain).toList(),
                b.reason(), b.expectedVersion());
    }

    // ------------------------------------------------------------------ billing rules

    @Operation(operationId = "getMembershipSettings", summary = "The membership billing rules (payments:read)")
    @GetMapping("/v1/memberships/admin/settings")
    SettingsDto getSettings() {
        return SettingsDto.of(settings.get());
    }

    @Operation(operationId = "updateMembershipSettings", summary = "Change the refund window and the retry schedule "
            + "(payments:configure)",
            description = "A reason is required, and the version the editor loaded.")
    @PutMapping("/v1/memberships/admin/settings")
    SettingsDto updateSettings(@Valid @RequestBody SettingsUpdate body) {
        return SettingsDto.of(settings.update(new MembershipSettingsService.UpdateCommand(body.refundWindowDays(),
                body.retryDays(), body.reason(), body.expectedVersion())));
    }

    @Operation(operationId = "listMembershipSettingsChanges", summary = "History of the billing rules, newest first "
            + "(payments:read)")
    @GetMapping("/v1/memberships/admin/settings/changes")
    List<SettingsChangeDto> settingsChanges(@RequestParam(defaultValue = "50") int limit) {
        return settings.history(limit).stream().map(SettingsChangeDto::of).toList();
    }

    // ------------------------------------------------------------------ members

    @Operation(operationId = "listMembershipMembers", summary = "Members, newest first (payments:read)",
            description = "Filter by tier and status; before pages backwards (the startedAt of the last member seen).")
    @GetMapping("/v1/memberships/admin/members")
    List<MembershipDto> members(@RequestParam(required = false) String tier,
                                @RequestParam(required = false) MembershipStatus status,
                                @RequestParam(required = false) Instant before,
                                @RequestParam(defaultValue = "50") int limit) {
        return memberships.members(tier, status, before, limit).stream().map(MembershipDto::of).toList();
    }

    @Operation(operationId = "listMembershipEvents", summary = "The timeline of a membership, oldest first "
            + "(payments:read)")
    @GetMapping("/v1/memberships/admin/members/{membershipId}/events")
    List<EventDto> events(@PathVariable UUID membershipId) {
        return memberships.timeline(membershipId).stream().map(EventDto::of).toList();
    }

    @Operation(operationId = "refundMembershipCharge", summary = "Refund a membership fee to the card "
            + "(payments:configure)",
            description = "The whole fee goes back to the card it was charged on, with a reason. When the charge paid "
                    + "the current period of a live membership, the membership ends now. This is a card refund of the "
                    + "fee; AfroTransact credit is never refunded to a card.")
    @PostMapping("/v1/memberships/admin/charges/{chargeId}/refund")
    ChargeDto refund(@PathVariable UUID chargeId, @Valid @RequestBody RefundRequest body) {
        return ChargeDto.of(memberships.refund(chargeId, body.reason()));
    }

    // ------------------------------------------------------------------ report

    @Operation(operationId = "membershipReport", summary = "Members per tier, churn, benefit cost, recurring revenue "
            + "and credit granted (payments:read)",
            description = "For the period from (inclusive) to to (inclusive), UTC dates; default the last 30 days. "
                    + "mrrMinor is paying members' price per month (a yearly fee divided by twelve).")
    @GetMapping("/v1/memberships/admin/report")
    ReportDto report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1L) : from;
        return ReportDto.of(reports.report(start.atStartOfDay().toInstant(ZoneOffset.UTC),
                end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
    }
}
