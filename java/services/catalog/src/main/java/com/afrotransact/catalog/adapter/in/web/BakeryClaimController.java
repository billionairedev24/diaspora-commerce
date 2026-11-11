package com.afrotransact.catalog.adapter.in.web;

import java.util.List;

import com.afrotransact.catalog.application.BakeryClaimService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Selling approvals (internal)", description = "Service-to-service: seller-service migrates the old bakery answer.")
@RestController
class BakeryClaimController {

    private final BakeryClaimService claims;

    BakeryClaimController(BakeryClaimService claims) {
        this.claims = claims;
    }

    @Operation(summary = "Organisations whose listings say the seller is a bakery (service scope catalog:bakery-claims.read)",
            description = "seller-service opens a PENDING food service application for each; the answer is never auto-approved.")
    @GetMapping("/v1/internal/bakery-claims")
    BakeryClaimsResponse bakeryClaims() {
        return new BakeryClaimsResponse(claims.organisations());
    }

    @Schema(name = "BakeryClaims")
    record BakeryClaimsResponse(List<String> organisations) {
    }
}
