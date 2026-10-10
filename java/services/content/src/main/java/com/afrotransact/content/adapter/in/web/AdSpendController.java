package com.afrotransact.content.adapter.in.web;

import java.time.LocalDate;
import java.util.List;

import com.afrotransact.content.application.AdSpendService;
import com.afrotransact.content.port.AdStatsRepository.OrgDaySpend;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Sponsored products", description = "Seller campaigns: CPC bids, budgets, targeting, reports (S127)")
@RestController
class AdSpendController {

    private final AdSpendService spend;

    AdSpendController(AdSpendService spend) {
        this.spend = spend;
    }

    @Schema(name = "AdOrgDaySpend", description = "Charged sponsored-products spend of one organisation on one UTC day")
    record OrgDaySpendResponse(@Schema(examples = "org_3k2j9x8q7w6e5r4t3y2u") String sellerOrgId,
                               @Schema(description = "UTC day") LocalDate day,
                               @Schema(examples = "USD") String currency, long clicks,
                               @Schema(description = "Charged clicks in minor units") long spendMinor) {

        static OrgDaySpendResponse of(OrgDaySpend s) {
            return new OrgDaySpendResponse(s.sellerOrgId(), s.day(), s.currency(), s.clicks(), s.spendMinor());
        }
    }

    @Schema(name = "AdSpendReport")
    record SpendResponse(LocalDate from, LocalDate to, List<OrgDaySpendResponse> items) {
    }

    @Operation(summary = "Charged ads spend per organisation and UTC day (metered billing, S206)",
            description = "Service scope ads:spend.read (seller-service) or staff with content:ads.view / "
                    + "content:ads.edit. Inclusive range of at most 93 days; only days with spend are listed. "
                    + "AfroTransact Retail's internal campaigns are never billed and are not listed.")
    @GetMapping("/v1/ads/spend")
    ResponseEntity<SpendResponse> spend(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        SpendResponse body = new SpendResponse(from, to, spend.spend(from, to).stream()
                .map(OrgDaySpendResponse::of).toList());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
