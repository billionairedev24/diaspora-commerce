package com.afrotransact.payments.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.application.MoneySettingsService;
import com.afrotransact.payments.application.MoneySettingsService.UpdateCommand;
import com.afrotransact.payments.domain.MoneySettings;
import com.afrotransact.payments.port.MoneySettingsRepository.Change;
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

@Tag(name = "Money settings", description = "Staff-configurable gift card limits and terms and the credit approval "
        + "limit (Settings, Money). Each change has a reason and is kept in a history.")
@RestController
class MoneySettingsController {

    private final MoneySettingsService settings;

    MoneySettingsController(MoneySettingsService settings) {
        this.settings = settings;
    }

    @Schema(name = "MoneySettings")
    record SettingsResponse(long giftCardMinMinor, long giftCardMaxMinor, List<Long> giftCardPresets,
                            Map<String, String> giftCardTerms,
                            @Schema(description = "Staff grants and adjustments of credit above this need a second "
                                    + "approver") long creditApprovalLimitMinor,
                            String updatedBy, Instant updatedAt,
                            @Schema(description = "Send it back with the next change; a concurrent change is 409")
                            long version) {

        static SettingsResponse of(MoneySettings s) {
            return new SettingsResponse(s.giftCardMinMinor(), s.giftCardMaxMinor(), s.giftCardPresets(),
                    s.giftCardTerms(), s.creditApprovalLimitMinor(), s.updatedBy(), s.updatedAt(), s.version());
        }
    }

    @Schema(name = "MoneySettingsUpdate")
    record UpdateRequest(
            @NotNull Long giftCardMinMinor,
            @NotNull Long giftCardMaxMinor,
            @NotNull @Size(max = 8) List<Long> giftCardPresets,
            @NotNull Map<String, String> giftCardTerms,
            @NotNull Long creditApprovalLimitMinor,
            @NotBlank @Size(max = 500) @Schema(description = "At least 15 characters") String reason,
            @NotNull Long version) {
    }

    @Schema(name = "MoneySettingsChange")
    record ChangeResponse(UUID id, String changedBy, Instant changedAt, String reason, String before,
                          String after) {

        static ChangeResponse of(Change c) {
            return new ChangeResponse(c.id(), c.changedBy(), c.changedAt(), c.reason(), c.beforeJson(),
                    c.afterJson());
        }
    }

    @Operation(operationId = "getMoneySettings", summary = "Money settings in force (payments:read)")
    @GetMapping("/v1/money/settings")
    SettingsResponse get() {
        return SettingsResponse.of(settings.get());
    }

    @Operation(operationId = "updateMoneySettings", summary = "Change the money settings with a reason (payments:configure)",
            description = "Takes effect at once for new purchases and grants; a concurrent change is 409 "
                    + "payments.money-settings-changed.")
    @PutMapping("/v1/money/settings")
    SettingsResponse update(@Valid @RequestBody UpdateRequest body) {
        return SettingsResponse.of(settings.update(new UpdateCommand(body.giftCardMinMinor(),
                body.giftCardMaxMinor(), body.giftCardPresets(), body.giftCardTerms(),
                body.creditApprovalLimitMinor(), body.reason(), body.version())));
    }

    @Operation(operationId = "moneySettingsHistory", summary = "Who changed the money settings, when and why (payments:read)")
    @GetMapping("/v1/money/settings/history")
    List<ChangeResponse> history(@RequestParam(defaultValue = "50") int limit) {
        return settings.history(limit).stream().map(ChangeResponse::of).toList();
    }
}
