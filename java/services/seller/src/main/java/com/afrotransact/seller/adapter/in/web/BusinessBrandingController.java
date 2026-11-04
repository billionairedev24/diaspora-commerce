package com.afrotransact.seller.adapter.in.web;

import com.afrotransact.seller.application.BusinessBrandingService;
import com.afrotransact.seller.application.PublicBusinessPage;
import com.afrotransact.seller.domain.BusinessSection;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Business page settings", description = "How a business's plain public page looks (description, logo, "
        + "brand colour). Members read; the owner and admins change it, at any time (also after approval).")
@RestController
@RequestMapping("/v1/orgs/{orgId}/business-page")
class BusinessBrandingController {

    private final BusinessBrandingService branding;

    BusinessBrandingController(BusinessBrandingService branding) {
        this.branding = branding;
    }

    @Schema(name = "BusinessPageSettings")
    record BusinessPageSettingsResponse(String orgId, @Schema(description = "null until the page is first made") String slug,
                                        String kind, String name, String description, String brandColor,
                                        String logoUrl, String contactEmail, boolean approved) {

        static BusinessPageSettingsResponse of(PublicBusinessPage p) {
            return new BusinessPageSettingsResponse(p.orgId(), p.slug(), p.kind(), p.name(), p.description(),
                    p.brandColor(), p.logoUrl(), p.contactEmail(), p.approved());
        }
    }

    @Schema(name = "BusinessPageSettingsRequest")
    record BusinessPageSettingsRequest(@Size(max = 2000) String description,
                                       @Schema(description = "#rrggbb; blank clears it") @Size(max = 7) String brandColor,
                                       @Schema(description = "https URL of an uploaded logo; blank removes it")
                                       @Size(max = 2048) String logoUrl) {
    }

    @Operation(summary = "The page settings as they are now")
    @GetMapping
    BusinessPageSettingsResponse get(@PathVariable String orgId) {
        return BusinessPageSettingsResponse.of(branding.get(orgId));
    }

    @Operation(summary = "Change how the page looks", description = "Blank values clear them. 422 "
            + "seller.invalid-branding with fieldErrors (description, brandColor, logoUrl).")
    @PutMapping
    BusinessPageSettingsResponse update(@PathVariable String orgId, @Valid @RequestBody BusinessPageSettingsRequest body) {
        return BusinessPageSettingsResponse.of(branding.update(orgId,
                new BusinessSection.Branding(body.description(), body.brandColor(), body.logoUrl())));
    }
}
