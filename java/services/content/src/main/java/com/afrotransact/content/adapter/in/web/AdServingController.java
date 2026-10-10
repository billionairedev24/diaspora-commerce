package com.afrotransact.content.adapter.in.web;

import java.util.Locale;

import com.afrotransact.content.adapter.in.web.AdDtos.ServableResponse;
import com.afrotransact.content.adapter.in.web.AdDtos.ServingResponse;
import com.afrotransact.content.application.AdServingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Sponsored products", description = "Seller campaigns: CPC bids, budgets, targeting, reports (S127)")
@RestController
class AdServingController {

    private final AdServingService serving;

    AdServingController(AdServingService serving) {
        this.serving = serving;
    }

    @Operation(summary = "Campaigns eligible to serve in a market, for search-service's auction (scope ads:serve)",
            description = "ACTIVE, inside the schedule, with budget left today. remainingMinor is the smaller of the "
                    + "lifetime and today's remaining budget. Bids are confidential: service and ads staff only.")
    @GetMapping("/v1/ads/serving")
    ResponseEntity<ServingResponse> serving(@RequestParam(defaultValue = "US") String market) {
        String m = market.trim().toUpperCase(Locale.ROOT);
        ServingResponse body = new ServingResponse(m, serving.eligible(m).stream().map(ServableResponse::of).toList());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
