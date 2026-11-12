package com.afrotransact.catalog.adapter.in.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.application.Blocker;
import com.afrotransact.catalog.application.CatalogMatchService.MatchCommand;
import com.afrotransact.catalog.application.CatalogMatchService.MatchResult;
import com.afrotransact.catalog.application.CatalogMatchService.MatchVariant;
import com.afrotransact.catalog.application.CatalogMatchService.PageImage;
import com.afrotransact.catalog.application.OfferEligibilityService.Eligibility;
import com.afrotransact.catalog.domain.ItemCondition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the match lookup and the offer eligibility check. */
final class MatchDtos {

    private MatchDtos() {
    }

    @Schema(name = "MatchRequest", description = "A barcode (UPC, EAN, GTIN or ISBN), or a brand with its manufacturer "
            + "part number. At least one of the two.")
    record MatchRequest(
            @Size(max = 20) @Schema(example = "012345678905", description = "Digits with optional spaces or dashes; 8, "
                    + "12, 13 or 14 digits, or an ISBN-10") String gtin,
            @Size(max = 120) @Schema(description = "Brand name or registry id (with mpn, for items without a barcode)")
            String brand,
            @Size(max = 80) @Schema(description = "Manufacturer part number (letters and digits are compared; case and "
                    + "punctuation ignored)") String mpn,
            @Schema(description = "Narrows a brand plus part number match to a category") UUID categoryId) {

        MatchCommand toCommand() {
            return new MatchCommand(gtin, brand, mpn, categoryId, null);
        }
    }

    @Schema(name = "Blocker", description = "Why the organisation cannot do something, in plain words")
    record BlockerDto(@NotBlank @Schema(description = "Stable code, for example catalog.page-not-live, "
            + "catalog.department-not-allowed, offers.selling-approval-required, catalog.brand-unavailable, "
            + "catalog.wrong-match-open") String code, @NotBlank String message) {

        static BlockerDto of(Blocker b) {
            return new BlockerDto(b.code(), b.message());
        }
    }

    @Schema(name = "PageImage")
    record PageImageDto(String url, Integer width, Integer height) {

        static PageImageDto of(PageImage i) {
            return i == null ? null : new PageImageDto(i.url(), i.width(), i.height());
        }
    }

    @Schema(name = "MatchVariant", description = "A child of the matched page the seller may offer too")
    record MatchVariantDto(String atin, Map<String, String> options, String gtin14) {

        static MatchVariantDto of(MatchVariant v) {
            return new MatchVariantDto(v.atin(), v.options(), v.gtin14());
        }
    }

    @Schema(name = "MatchResult")
    record MatchResponse(
            @Schema(allowableValues = {"MATCH", "NO_MATCH", "INVALID", "RESTRICTED"}) String result,
            @Schema(description = "Present on MATCH", allowableValues = {"GTIN", "BRAND_MPN"}) String matchedOn,
            @Schema(description = "The normalised 14-digit key (null for a brand plus part number lookup or an "
                    + "unreadable code)", pattern = "[0-9]{14}") String gtin14,
            @Schema(description = "The cleaned input digits (an ISBN-10 shown as its ISBN-13)") String digits,
            @Schema(description = "ATIN of the matched child (variant); offers attach to this. Null when a brand plus "
                    + "part number match names a page with several children: pick one of variants") String atin,
            @Schema(description = "ATIN of the product page") String parentAtin,
            String productStatus, String title, String brand, PageImageDto image,
            @Schema(description = "Category path") String category, UUID categoryId,
            Map<String, String> variantOptions,
            @Schema(description = "Every child of the matched page (the seller may offer the others too)")
            List<MatchVariantDto> variants,
            boolean canOffer, List<BlockerDto> blockers,
            @Schema(description = "False when a new page with this barcode must not be created (a match exists, the "
                    + "barcode is invalid or restricted, or a wrong-match report on it is open)") boolean canCreate,
            List<BlockerDto> createBlockers,
            @Schema(description = "A corrected barcode when only the check digit is wrong") String suggestion,
            @Schema(description = "Plain words for INVALID and RESTRICTED") String message) {

        static MatchResponse of(MatchResult r) {
            return new MatchResponse(r.result().name(), r.matchedOn() == null ? null : r.matchedOn().name(), r.gtin14(),
                    r.digits(), r.atin(), r.parentAtin(), r.productStatus(), r.title(), r.brand(),
                    PageImageDto.of(r.image()), r.category(), r.categoryId(), r.variantOptions(),
                    r.variants().stream().map(MatchVariantDto::of).toList(), r.canOffer(),
                    r.blockers().stream().map(BlockerDto::of).toList(), r.canCreate(),
                    r.createBlockers().stream().map(BlockerDto::of).toList(), r.suggestion(), r.message());
        }
    }

    @Schema(name = "OfferEligibilityRequest")
    record EligibilityRequest(
            @NotBlank @Pattern(regexp = "AT[0-9A-Z]{8}") @Schema(description = "Variant ATIN") String atin,
            @Schema(description = "Service and staff tokens only") String sellerOrgId) {
    }

    @Schema(name = "OfferEligibility")
    record EligibilityResponse(String atin, String productAtin, boolean canOffer, List<BlockerDto> blockers,
                               @Schema(description = "Conditions the category allows (NEW only unless used items are "
                                       + "allowed, listing rule D9)") List<ItemCondition> allowedConditions,
                               String pageOwnerOrgId) {

        static EligibilityResponse of(Eligibility e) {
            return new EligibilityResponse(e.atin(), e.productAtin(), e.canOffer(),
                    e.blockers().stream().map(BlockerDto::of).toList(), e.allowedConditions(), e.pageOwnerOrgId());
        }
    }
}
