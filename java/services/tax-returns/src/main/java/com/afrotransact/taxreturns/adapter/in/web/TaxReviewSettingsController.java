package com.afrotransact.taxreturns.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.taxreturns.application.TaxReviewSettingsService;
import com.afrotransact.taxreturns.application.TaxReviewSettingsService.ReviewSettingsView;
import com.afrotransact.taxreturns.domain.profile.ReviewMode;
import com.afrotransact.taxreturns.domain.profile.ReviewSettings;
import com.afrotransact.taxreturns.domain.profile.ReviewSettingsChange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** How variant classifications reach tax review: every one (ALL, default) or by risk (staff, tax:read / tax:classify-admin). */
@RestController
@Tag(name = "Tax settings")
class TaxReviewSettingsController {

    private final TaxReviewSettingsService settings;

    TaxReviewSettingsController(TaxReviewSettingsService settings) {
        this.settings = settings;
    }

    @Schema(name = "TaxReviewSettingsValues")
    record ValuesDto(@Schema(allowableValues = {"ALL", "RISK_BASED"}) String mode, List<String> alwaysReview,
                     int auditPercent) {

        static ValuesDto of(ReviewSettings s) {
            return new ValuesDto(s.mode().name(), s.alwaysReview(), s.auditPercent());
        }
    }

    @Schema(name = "TaxReviewSettingsChange")
    record ChangeDto(ValuesDto from, ValuesDto to, String reason, String changedBy, Instant changedAt) {

        static ChangeDto of(ReviewSettingsChange c) {
            return new ChangeDto(c.from() == null ? null : ValuesDto.of(c.from()), ValuesDto.of(c.to()), c.reason(),
                    c.changedBy(), c.changedAt());
        }
    }

    @Schema(name = "TaxReviewSettings")
    record SettingsDto(@Schema(allowableValues = {"ALL", "RISK_BASED"},
                               description = "ALL: every classification waits for a tax reviewer (the default). "
                                       + "RISK_BASED: only when the category is ACTIVE, the rules resolve and it is "
                                       + "not on the always-review list; never switch on before the Texas tax "
                                       + "professional agrees.") String mode,
                       @Schema(description = "Category codes that always go to review in RISK_BASED mode")
                       List<String> alwaysReview,
                       @Schema(description = "Categories whose status always sends them to review whatever the list says")
                       List<String> alwaysReviewStatuses,
                       @Schema(description = "Share of auto-approved items also queued as audit items, 0 to 100")
                       int auditPercent, String changedBy, Instant changedAt, List<ChangeDto> history) {

        static SettingsDto of(ReviewSettingsView v) {
            return new SettingsDto(v.settings().mode().name(), v.settings().alwaysReview(), v.alwaysReviewAlso(),
                    v.settings().auditPercent(), v.changedBy(), v.changedAt(),
                    v.history().stream().map(ChangeDto::of).toList());
        }
    }

    record ChangeRequest(@NotNull ReviewMode mode,
                         @Schema(description = "Replaces the always-review list; absent keeps the current list")
                         List<String> alwaysReview,
                         @Schema(description = "0 to 100; absent keeps the current percentage") Integer auditPercent,
                         @NotBlank String reason) {
    }

    @Operation(summary = "The tax review mode, always-review list, audit sample percentage and their history (tax:read)")
    @GetMapping("/v1/tax/review-settings")
    SettingsDto get() {
        return SettingsDto.of(settings.current());
    }

    @Operation(summary = "Set the tax review mode, always-review list and audit sample percentage (tax:classify-admin)",
            description = "taxReview.mode is ALL until staff change it. RISK_BASED lets the system approve a "
                    + "classification only when the category is ACTIVE, the rules resolve cleanly, no required evidence "
                    + "is missing and the category is not on the always-review list; everything else stays in the "
                    + "review queue. Never switch it on before the Texas tax professional agrees. A reason of 10+ "
                    + "characters is required and the change is recorded. It applies to new classification events; "
                    + "records already decided keep their status. 422 tax.unknown-category, tax.invalid-audit-percent.")
    @PutMapping("/v1/tax/review-settings")
    SettingsDto change(@Valid @RequestBody ChangeRequest body) {
        return SettingsDto.of(settings.change(body.mode(), body.alwaysReview(), body.auditPercent(), body.reason()));
    }
}
