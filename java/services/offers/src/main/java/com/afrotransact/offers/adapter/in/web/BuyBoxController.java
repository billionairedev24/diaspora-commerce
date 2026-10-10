package com.afrotransact.offers.adapter.in.web;

import java.util.List;

import com.afrotransact.offers.adapter.in.web.BuyBoxDtos.ExplanationResponse;
import com.afrotransact.offers.adapter.in.web.BuyBoxDtos.PerformanceRequest;
import com.afrotransact.offers.adapter.in.web.BuyBoxDtos.StandingResponse;
import com.afrotransact.offers.application.BuyBoxService;
import com.afrotransact.offers.application.SellerPerformanceCommand;
import com.afrotransact.offers.application.SellerStandingService;
import com.afrotransact.offers.port.BuyBoxRepository.AuditEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Buy box", description = "Why an offer wins or loses, audit trail and seller performance inputs")
@RestController
class BuyBoxController {

    private final BuyBoxService buyBox;
    private final SellerStandingService standings;

    BuyBoxController(BuyBoxService buyBox, SellerStandingService standings) {
        this.buyBox = buyBox;
        this.standings = standings;
    }

    @Operation(summary = "Score breakdown of an ATIN's buy box (offers:read)",
            description = "Every factor's points with a sentence; ineligible offers list why.")
    @GetMapping("/v1/buy-boxes/{atin}/explanation")
    ExplanationResponse explain(@PathVariable String atin, @RequestParam(defaultValue = "USD") String currency) {
        return ExplanationResponse.of(buyBox.explain(atin, currency));
    }

    @Operation(summary = "Audit trail of winner changes, newest first (staff)")
    @GetMapping("/v1/buy-boxes/{atin}/history")
    List<AuditEntry> history(@PathVariable String atin, @RequestParam(defaultValue = "50") int limit) {
        return buyBox.history(atin, limit);
    }

    @Operation(summary = "Seller standing used by the buy box (own org; staff any)")
    @GetMapping("/v1/sellers/{orgId}/standing")
    StandingResponse standing(@PathVariable String orgId) {
        return StandingResponse.of(standings.get(orgId));
    }

    @Operation(summary = "Set seller performance inputs (staff / seller-health job with offers:write)",
            description = "Recomputes the buy box of every ATIN the seller offers.")
    @PutMapping("/v1/sellers/{orgId}/performance")
    StandingResponse performance(@PathVariable String orgId, @Valid @RequestBody PerformanceRequest body) {
        return StandingResponse.of(standings.updatePerformance(orgId, new SellerPerformanceCommand(body.rating(),
                body.onTimeRate(), body.defectRate(), body.restricted())));
    }
}
