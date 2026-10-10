package com.afrotransact.catalog.adapter.in.web;

import com.afrotransact.catalog.adapter.in.web.ProductDtos.FlagResponse;
import com.afrotransact.catalog.adapter.in.web.ProductDtos.ReportRequest;
import com.afrotransact.catalog.application.ListingModerationService;
import com.afrotransact.catalog.application.ListingModerationService.ReportResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Listing reports", description = "Shoppers report a live listing (signed in); reports reach the "
        + "trust-ops moderation queue through catalog.product.flagged")
@RestController
class ListingReportController {

    private final ListingModerationService listings;

    ListingReportController(ListingModerationService listings) {
        this.listings = listings;
    }

    @Operation(summary = "Report a live listing by product ATIN (any signed-in user)",
            description = "One report per user and listing: a repeated report returns 200 with the earlier report "
                    + "(created=false) and publishes nothing. Listings that are not publicly visible return 404.")
    @PostMapping("/v1/public/products/{atin}/reports")
    ResponseEntity<FlagResponse> reportListing(@PathVariable String atin, @Valid @RequestBody ReportRequest body) {
        ReportResult r = listings.report(atin, body.reasonCode(), body.details());
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(FlagResponse.of(r.flag(), r.created()));
    }
}
