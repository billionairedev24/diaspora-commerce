package com.afrotransact.catalog.adapter.in.web;

import com.afrotransact.catalog.adapter.in.web.MatchDtos.EligibilityRequest;
import com.afrotransact.catalog.adapter.in.web.MatchDtos.EligibilityResponse;
import com.afrotransact.catalog.adapter.in.web.MatchDtos.MatchRequest;
import com.afrotransact.catalog.adapter.in.web.MatchDtos.MatchResponse;
import com.afrotransact.catalog.application.CatalogMatchService;
import com.afrotransact.catalog.application.OfferEligibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catalog matching", description = "One lookup from a barcode or a brand with a part number to the existing "
        + "product page (S381, S383): the seller sells an offer on the page and never edits its content")
@RestController
class MatchController {

    private final CatalogMatchService matches;
    private final OfferEligibilityService eligibility;

    MatchController(CatalogMatchService matches, OfferEligibilityService eligibility) {
        this.matches = matches;
        this.eligibility = eligibility;
    }

    @Operation(operationId = "match",
            summary = "Find the existing product page for a barcode, or for a brand plus part number (S381)",
            description = "The barcode is cleaned, an ISBN-10 becomes its ISBN-13 and the 14-digit key makes the UPC and "
                    + "the EAN of one item meet. INVALID and RESTRICTED are answers, not errors. Needs catalog:write "
                    + "(sellers) or catalog:read (service clients).")
    @PostMapping("/v1/catalog/matches")
    MatchResponse match(@Valid @RequestBody MatchRequest request) {
        return MatchResponse.of(matches.match(request.toCommand()));
    }

    @Operation(operationId = "offerEligibility",
            summary = "May this organisation attach an offer to this variant? (service clients, S383)",
            description = "Used by offers-service before it stores an offer: the page is live, the brand is not retired "
                    + "or rejected, the department is in scope and the selling approval is granted. A refusal is "
                    + "canOffer=false with blockers, not an error.")
    @PostMapping("/v1/catalog/offer-eligibility")
    EligibilityResponse offerEligibility(@Valid @RequestBody EligibilityRequest request) {
        return EligibilityResponse.of(eligibility.eligibility(request.atin(), request.sellerOrgId()));
    }
}
