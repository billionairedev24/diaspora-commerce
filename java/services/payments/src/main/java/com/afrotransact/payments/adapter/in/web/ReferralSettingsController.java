package com.afrotransact.payments.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.application.ReferralSettingsService;
import com.afrotransact.payments.application.ReferralSettingsService.UpdateCommand;
import com.afrotransact.payments.domain.ReferralReward;
import com.afrotransact.payments.domain.ReferralSettings;
import com.afrotransact.payments.domain.RewardType;
import com.afrotransact.payments.port.ReferralSettingsRepository.Change;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Referral settings", description = "Staff-configurable referral program (Settings, Money or Growth): each "
        + "side's reward, the qualifying event, minimum order, return-window delay, caps, validity and on/off. Each "
        + "change has a reason and is kept in a history.")
@RestController
class ReferralSettingsController {

    private final ReferralSettingsService settings;

    ReferralSettingsController(ReferralSettingsService settings) {
        this.settings = settings;
    }

    @Schema(name = "ReferralRewardSetting", description = "What one side earns: credit or free membership days")
    record RewardSetting(@NotNull @Schema(allowableValues = {"CREDIT", "MEMBERSHIP_DAYS"}) RewardType type,
                         @Schema(description = "Credit in minor units; 0 unless type is CREDIT") long creditMinor,
                         @Schema(description = "Free membership days; 0 unless type is MEMBERSHIP_DAYS")
                         int membershipDays) {

        static RewardSetting of(ReferralReward r) {
            return new RewardSetting(r.type(), r.creditMinor(), r.membershipDays());
        }

        ReferralReward toDomain() {
            return new ReferralReward(type, creditMinor, membershipDays);
        }
    }

    @Schema(name = "ReferralSettings")
    record SettingsResponse(boolean enabled, RewardSetting referrer, RewardSetting referred,
                            @Schema(allowableValues = {"FIRST_ORDER_DELIVERED"}) String qualifyingEvent,
                            long minOrderMinor, int returnWindowDays,
                            @Schema(description = "Rewarded referrals one customer can have; 0 is no limit")
                            int maxRewardedPerReferrer,
                            @Schema(description = "Referral credit one customer can earn in all; 0 is no limit")
                            long maxCreditPerCustomerMinor, int validityDays, String updatedBy, Instant updatedAt,
                            @Schema(description = "Send it back with the next change; a concurrent change is 409")
                            long version) {

        static SettingsResponse of(ReferralSettings s) {
            return new SettingsResponse(s.enabled(), RewardSetting.of(s.referrer()), RewardSetting.of(s.referred()),
                    s.qualifyingEvent().name(), s.minOrderMinor(), s.returnWindowDays(), s.maxRewardedPerReferrer(),
                    s.maxCreditPerCustomerMinor(), s.validityDays(), s.updatedBy(), s.updatedAt(), s.version());
        }
    }

    @Schema(name = "ReferralSettingsUpdate")
    record UpdateRequest(
            @NotNull Boolean enabled,
            @NotNull @Valid RewardSetting referrer,
            @NotNull @Valid RewardSetting referred,
            @NotNull ReferralSettings.QualifyingEvent qualifyingEvent,
            @NotNull Long minOrderMinor,
            @NotNull Integer returnWindowDays,
            @NotNull Integer maxRewardedPerReferrer,
            @NotNull Long maxCreditPerCustomerMinor,
            @NotNull Integer validityDays,
            @NotBlank @Size(max = 500) @Schema(description = "At least 15 characters") String reason,
            @NotNull Long version) {
    }

    @Schema(name = "ReferralSettingsChange")
    record ChangeResponse(UUID id, String changedBy, Instant changedAt, String reason, String before,
                          String after) {

        static ChangeResponse of(Change c) {
            return new ChangeResponse(c.id(), c.changedBy(), c.changedAt(), c.reason(), c.beforeJson(),
                    c.afterJson());
        }
    }

    @Operation(operationId = "getReferralSettings", summary = "Referral settings in force (payments:read)")
    @GetMapping("/v1/referral-settings")
    SettingsResponse get() {
        return SettingsResponse.of(settings.get());
    }

    @Operation(operationId = "updateReferralSettings",
            summary = "Change the referral settings with a reason (payments:configure)",
            description = "Takes effect at once for new sign-ups and for referrals that qualify afterwards; rewards "
                    + "already promised keep their amounts. A concurrent change is 409 "
                    + "payments.referral-settings-changed.")
    @PutMapping("/v1/referral-settings")
    SettingsResponse update(@Valid @RequestBody UpdateRequest body) {
        return SettingsResponse.of(settings.update(new UpdateCommand(body.enabled(), body.referrer().toDomain(),
                body.referred().toDomain(), body.qualifyingEvent(), body.minOrderMinor(), body.returnWindowDays(),
                body.maxRewardedPerReferrer(), body.maxCreditPerCustomerMinor(), body.validityDays(), body.reason(),
                body.version())));
    }

    @Operation(operationId = "referralSettingsHistory",
            summary = "Who changed the referral settings, when and why (payments:read)")
    @GetMapping("/v1/referral-settings/history")
    List<ChangeResponse> history(@RequestParam(defaultValue = "50") int limit) {
        return settings.history(limit).stream().map(ChangeResponse::of).toList();
    }
}
