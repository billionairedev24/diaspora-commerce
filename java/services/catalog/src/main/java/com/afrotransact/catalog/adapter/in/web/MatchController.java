package com.afrotransact.catalog.adapter.in.web;

import com.afrotransact.catalog.adapter.in.web.CandidateDtos.CandidateRequest;
import com.afrotransact.catalog.adapter.in.web.CandidateDtos.CandidateResponse;
import com.afrotransact.catalog.adapter.in.web.CandidateDtos.DecisionRequest;
import com.afrotransact.catalog.adapter.in.web.CandidateDtos.DecisionResponse;
import com.afrotransact.catalog.adapter.in.web.CandidateDtos.SizeRequestRequest;
import com.afrotransact.catalog.adapter.in.web.CandidateDtos.SizeRequestResponse;
import com.afrotransact.catalog.adapter.in.web.MatchDtos.EligibilityRequest;
import com.afrotransact.catalog.adapter.in.web.MatchDtos.EligibilityResponse;
import com.afrotransact.catalog.adapter.in.web.MatchDtos.MatchRequest;
import com.afrotransact.catalog.adapter.in.web.MatchDtos.MatchResponse;
import com.afrotransact.catalog.application.CandidateMatchService;
import com.afrotransact.catalog.application.CatalogMatchService;
import com.afrotransact.catalog.application.OfferEligibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catalog matching", description = "Finding the existing product page: a search by name (no barcode "
        + "needed) or the optional barcode shortcut (S381, S383). The seller sells on the page and never edits its content")
@RestController
class MatchController {

    private final CatalogMatchService matches;
    private final CandidateMatchService candidates;
    private final OfferEligibilityService eligibility;

    MatchController(CatalogMatchService matches, CandidateMatchService candidates, OfferEligibilityService eligibility) {
        this.matches = matches;
        this.candidates = candidates;
        this.eligibility = eligibility;
    }

    @Operation(operationId = "match",
            summary = "Find the existing product page for a barcode (optional exact shortcut, S381)",
            description = "The barcode is cleaned, an ISBN-10 becomes its ISBN-13 and the 14-digit key makes the UPC and "
                    + "the EAN of one item meet. INVALID and RESTRICTED are answers, not errors. Needs catalog:write "
                    + "(sellers) or catalog:read (service clients).")
    @PostMapping("/v1/catalog/matches")
    MatchResponse match(@Valid @RequestBody MatchRequest request) {
        return MatchResponse.of(matches.match(request.toCommand()));
    }

    @Operation(operationId = "matchCandidates",
            summary = "Is your product already on AfroTransact? Up to six pages for a name, no barcode needed",
            description = "Search-first matching. The name carries the brand and the size; the category is optional and "
                    + "suggested when missing. Only live pages with a real photo are returned, ranked by name, brand, "
                    + "size and category. SAME_NAME is exact, SIMILAR is a suggestion the seller must confirm. Nothing "
                    + "about other sellers is returned. Limited per organisation (422 catalog.match-rate-limited). "
                    + "Needs catalog:write (sellers) or catalog:read (service clients).")
    @PostMapping("/v1/catalog/matches/candidates")
    CandidateResponse candidates(@Valid @RequestBody CandidateRequest request) {
        return CandidateResponse.of(candidates.candidates(request.toCommand()));
    }

    @Operation(operationId = "matchDecision",
            summary = "Record the seller's answer: sell this existing page, or None of these",
            description = "SELL_EXISTING checks that the variant was in the list and that the size was confirmed "
                    + "(catalog.match-size-unconfirmed), then returns whether the organisation may sell it. "
                    + "NONE_OF_THESE is stored; when a likely page was shown next to it, staff are asked to compare the "
                    + "new page with it later. Never blocks adding a new page.")
    @PostMapping("/v1/catalog/matches/decisions")
    DecisionResponse decision(@Valid @RequestBody DecisionRequest request) {
        return DecisionResponse.of(candidates.decide(request.toCommand()));
    }

    @Operation(operationId = "matchSizeRequest",
            summary = "Sell a different size of a page that exists: ask for it to be added as a variant",
            description = "For a seller whose product is on the page but in another size, weight or pack. Files a "
                    + "NEW_VARIANT request that catalogue staff check like any new variant (own ATIN under the page); "
                    + "no duplicate page is created and the page is not changed by this call. A size the page already has "
                    + "answers ALREADY_ON_PAGE with the variant to sell; the same size asked twice answers "
                    + "ALREADY_REQUESTED. 422 catalog.variant-size-invalid, catalog.variant-page-size-unknown, "
                    + "catalog.variant-theme-unsupported, catalog.variant-request-blocked, catalog.match-choice-not-shown, "
                    + "catalog.match-rate-limited (too many waiting). The seller adds price and stock once the request is "
                    + "applied (the variant ATIN is on the request).")
    @PostMapping("/v1/catalog/matches/size-requests")
    SizeRequestResponse sizeRequest(@Valid @RequestBody SizeRequestRequest request) {
        return SizeRequestResponse.of(candidates.requestSize(request.toCommand()));
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
