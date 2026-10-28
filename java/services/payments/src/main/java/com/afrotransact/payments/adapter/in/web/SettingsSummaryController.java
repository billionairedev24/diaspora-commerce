package com.afrotransact.payments.adapter.in.web;

import java.util.List;

import com.afrotransact.payments.application.SettingsSummaryService;
import com.afrotransact.payments.application.SettingsSummaryService.SettingLine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Settings")
@RestController
class SettingsSummaryController {

    private final SettingsSummaryService summary;

    SettingsSummaryController(SettingsSummaryService summary) {
        this.summary = summary;
    }

    @Schema(name = "PaymentsSetting")
    record SettingResponse(String key, String label, String value, String help,
                           @Schema(description = "Where it is set: the environment setting that changes it")
                           String source) {

        static SettingResponse of(SettingLine l) {
            return new SettingResponse(l.key(), l.label(), l.value(), l.help(), l.source());
        }
    }

    @Operation(summary = "What governs holds, payouts and Stripe, in words (payments:read)",
            description = "Read-only: the values come from the deployment's configuration.")
    @GetMapping("/v1/settings/summary")
    List<SettingResponse> summary() {
        return summary.summary().stream().map(SettingResponse::of).toList();
    }
}
