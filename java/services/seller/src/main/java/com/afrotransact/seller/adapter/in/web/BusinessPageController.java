package com.afrotransact.seller.adapter.in.web;

import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.application.BusinessPageService;
import com.afrotransact.seller.application.PublicBusinessPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Business pages", description = "The plain public page of a business that has no website of its own "
        + "(what Stripe is given as its website). Public, read-only.")
@RestController
class BusinessPageController {

    private final BusinessPageService pages;

    BusinessPageController(BusinessPageService pages) {
        this.pages = pages;
    }

    @Schema(name = "BusinessPage")
    record BusinessPageResponse(String orgId, String slug, String kind, String name, String description,
                                @Schema(description = "#rrggbb, or null") String brandColor,
                                @Schema(description = "https URL, or null") String logoUrl, String contactEmail,
                                @Schema(description = "false until the organisation is approved: keep it out of search "
                                        + "engines") boolean approved) {

        static BusinessPageResponse of(PublicBusinessPage p) {
            return new BusinessPageResponse(p.orgId(), p.slug(), p.kind(), p.name(), p.description(), p.brandColor(),
                    p.logoUrl(), p.contactEmail(), p.approved());
        }
    }

    @Operation(summary = "A business's plain public page (no login)")
    @GetMapping("/v1/public/business-pages/{slug}")
    BusinessPageResponse page(@PathVariable String slug) {
        return pages.find(slug).map(BusinessPageResponse::of).orElseThrow(() -> new NotFoundException(
                "seller.business-page-not-found", "No page at " + slug));
    }
}
