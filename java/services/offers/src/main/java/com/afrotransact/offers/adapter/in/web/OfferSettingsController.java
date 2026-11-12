package com.afrotransact.offers.adapter.in.web;

import java.time.Instant;

import com.afrotransact.offers.application.OfferSettingsService;
import com.afrotransact.offers.domain.OfferPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Offer settings", description = "Staff-editable thresholds of matched offers and the tax release")
@RestController
class OfferSettingsController {

    private final OfferSettingsService settings;

    OfferSettingsController(OfferSettingsService settings) {
        this.settings = settings;
    }

    @Schema(name = "OfferPolicySettings", description = "Thresholds of matched offers and the tax release, editable by staff")
    record PolicyDto(
            @Schema(description = "The longest note on a used or renewed item", defaultValue = "200")
            int conditionNoteMaxLength,
            @Schema(description = "The longest edge of a Fulfilled by AfroTransact box, in inches", defaultValue = "108")
            double packageMaxEdgeIn,
            @Schema(description = "The heaviest Fulfilled by AfroTransact box, in pounds", defaultValue = "150")
            double packageMaxWeightLb,
            @Schema(description = "How many held or unverified offers one tax release run looks at",
                    defaultValue = "200") int taxSweepBatchSize,
            String updatedBy, Instant updatedAt, long version) {

        static PolicyDto of(OfferPolicy p) {
            return new PolicyDto(p.conditionNoteMaxLength(), p.packageMaxEdgeIn(), p.packageMaxWeightLb(),
                    p.taxSweepBatchSize(), p.updatedBy(), p.updatedAt(), p.version());
        }

        OfferPolicy toDomain() {
            return new OfferPolicy(conditionNoteMaxLength, packageMaxEdgeIn, packageMaxWeightLb, taxSweepBatchSize, null,
                    null, version);
        }
    }

    @Operation(operationId = "getOfferSettings", summary = "Matched-offer and tax release settings (staff)")
    @GetMapping("/v1/offers/settings")
    PolicyDto get() {
        return PolicyDto.of(settings.view());
    }

    @Operation(operationId = "updateOfferSettings",
            summary = "Save the settings (staff; audited by updatedBy and version)",
            description = "409 offers.concurrent-update when version is stale; 422 for an invalid value.")
    @PutMapping("/v1/offers/settings")
    PolicyDto update(@Valid @RequestBody PolicyDto body) {
        return PolicyDto.of(settings.update(body.toDomain()));
    }
}
