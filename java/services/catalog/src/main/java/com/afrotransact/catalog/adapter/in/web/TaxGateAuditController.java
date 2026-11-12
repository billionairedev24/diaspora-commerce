package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.application.LiveTaxAuditService;
import com.afrotransact.catalog.application.LiveTaxAuditService.Audit;
import com.afrotransact.catalog.application.TaxGateSweepService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Products", description = "Product builder, listing moderation and lookup")
@RestController
class TaxGateAuditController {

    private final LiveTaxAuditService audit;
    private final TaxGateSweepService sweep;

    TaxGateAuditController(LiveTaxAuditService audit, TaxGateSweepService sweep) {
        this.audit = audit;
        this.sweep = sweep;
    }

    @Schema(name = "TaxGateSweepResult", description = "What one run of the tax gate sweep did")
    record SweepResultBody(int closed, int closedByTaxReturns, int reopened, int announced, int checked,
                           @Schema(description = "False when tax-returns could not be asked: nothing was marked OK and "
                                   + "every state was kept") boolean taxReturnsReachable) {

        static SweepResultBody of(TaxGateSweepService.Result r) {
            return new SweepResultBody(r.closed(), r.closedByTaxReturns(), r.reopened(), r.announced(), r.checked(),
                    r.authorityReachable());
        }
    }

    @Operation(summary = "Run the tax gate sweep now (staff, tax:classify-admin)",
            description = "The repair path behind the scheduled sweep: closes every live listing whose variants tax-returns "
                    + "(the decision checkout reads) does not answer RESOLVED for, re-reports facts tax-returns has no record "
                    + "of so they reach the Tax review queue, and reopens held listings that are now resolved. Safe to repeat.")
    @PostMapping("/v1/tax-gate/sweep")
    SweepResultBody runSweep() {
        return SweepResultBody.of(sweep.sweepNowAsStaff());
    }

    @Schema(name = "LiveWithoutTaxDecisionPage", description = "Live listings that have a variant without a stored, "
            + "resolved tax decision in tax-returns (the decision checkout reads). Must always be empty.")
    record LiveWithoutTaxDecisionPage(List<LiveWithoutTaxDecisionItem> items, long total, int page, int size,
                                      @Schema(description = "False when tax-returns could not be asked: only catalog's own "
                                              + "check ran and the list may be incomplete") boolean taxReturnsChecked) {

        static LiveWithoutTaxDecisionPage of(Audit a) {
            return new LiveWithoutTaxDecisionPage(a.items().stream().map(LiveWithoutTaxDecisionItem::of).toList(),
                    a.total(), a.page(), a.size(), a.authorityChecked());
        }
    }

    @Schema(name = "LiveWithoutTaxDecisionItem")
    record LiveWithoutTaxDecisionItem(UUID productId, String atin, String title, String ownerOrgId,
                                      String categoryName, String status, Instant updatedAt, List<LiveWithoutTaxDecisionVariant> variants) {

        static LiveWithoutTaxDecisionItem of(LiveTaxAuditService.Item i) {
            return new LiveWithoutTaxDecisionItem(i.productId(), i.atin(), i.title(), i.ownerOrgId(), i.categoryName(),
                    i.status(), i.updatedAt(), i.variants().stream()
                            .map(v -> new LiveWithoutTaxDecisionVariant(v.atin(), v.sellerSku(), v.taxStatus(), v.reason()))
                            .toList());
        }
    }

    @Schema(name = "LiveWithoutTaxDecisionVariant")
    record LiveWithoutTaxDecisionVariant(String atin, String sellerSku, String taxStatus, String reason) {
    }

    @Operation(summary = "Live listings without a resolved tax decision (staff, tax:read)",
            description = "The audit behind catalog_live_without_tax_decision: live products that have a variant whose tax "
                    + "gate is not READY or that tax-returns does not answer RESOLVED for. Always empty when the publish gate holds; "
                    + "the sweep closes anything listed here (POST /v1/tax-gate/sweep runs it now).")
    @GetMapping("/v1/tax-gate/live-without-decision")
    LiveWithoutTaxDecisionPage liveWithoutDecision(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "25") int size) {
        return LiveWithoutTaxDecisionPage.of(audit.liveWithoutTaxDecision(page, size));
    }
}
