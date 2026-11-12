package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.catalog.application.CatalogSettingsService;
import com.afrotransact.catalog.domain.CatalogPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catalog settings", description = "Staff-editable thresholds for matching, exemptions and the review queue")
@RestController
class CatalogSettingsController {

    private final CatalogSettingsService settings;

    CatalogSettingsController(CatalogSettingsService settings) {
        this.settings = settings;
    }

    @Schema(name = "CatalogPolicySettings", description = "Every threshold of matching, exemptions and the review "
            + "queue, editable by staff")
    record PolicyDto(
            @NotNull @Schema(description = "Prefixes of the GTIN-13 form that are store-use or coupon numbers; seeded "
                    + "with 02, 04, 20 to 29, 981 to 984 and 99") List<String> reservedPrefixes,
            @NotBlank @Schema(description = "The product attribute that holds the manufacturer part number",
                    defaultValue = "model") String mpnAttributeKey,
            @Min(1) @Max(30) @Schema(defaultValue = "2") int reviewSlaBusinessDays,
            @Min(1) @Max(60) @Schema(defaultValue = "3") int alertAfterBusinessDays,
            @Schema(defaultValue = "1000") int noteMaxLength,
            @Min(1) @Schema(description = "A brand with at least this many pages that carry barcodes counts as a known "
                    + "GS1 brand (a no-barcode item of it needs staff approval)", defaultValue = "1")
            int knownGs1BrandMinPages,
            @NotNull @Schema(description = "Product-type codes that are brand-gated; a no-barcode item in them needs "
                    + "staff approval") List<String> exemptionGatedCategoryCodes,
            @Schema(defaultValue = "true") boolean exemptionRequiresApprovalForKnownBrands,
            String updatedBy, Instant updatedAt, long version) {

        static PolicyDto of(CatalogPolicy p) {
            return new PolicyDto(p.reservedPrefixes(), p.mpnAttributeKey(), p.reviewSlaBusinessDays(),
                    p.alertAfterBusinessDays(), p.noteMaxLength(), p.knownGs1BrandMinPages(),
                    p.exemptionGatedCategoryCodes(), p.exemptionRequiresApprovalForKnownBrands(), p.updatedBy(),
                    p.updatedAt(), p.version());
        }

        CatalogPolicy toDomain() {
            return new CatalogPolicy(reservedPrefixes, mpnAttributeKey, reviewSlaBusinessDays, alertAfterBusinessDays,
                    noteMaxLength, knownGs1BrandMinPages, exemptionGatedCategoryCodes,
                    exemptionRequiresApprovalForKnownBrands, null, null, version);
        }
    }

    @Operation(operationId = "getCatalogSettings", summary = "Matching, exemption and review-queue settings (catalog:moderate)")
    @GetMapping("/v1/catalog/settings")
    PolicyDto get() {
        return PolicyDto.of(settings.view());
    }

    @Operation(operationId = "updateCatalogSettings",
            summary = "Save the settings (catalog:moderate, audited by updatedBy and version)",
            description = "409 catalog.concurrent-update when version is stale; 422 for an invalid value.")
    @PutMapping("/v1/catalog/settings")
    PolicyDto update(@Valid @RequestBody PolicyDto body) {
        return PolicyDto.of(settings.update(body.toDomain()));
    }
}
