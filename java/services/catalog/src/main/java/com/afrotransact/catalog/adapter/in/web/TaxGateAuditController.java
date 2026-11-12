package com.afrotransact.catalog.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.application.LiveTaxAuditService;
import com.afrotransact.catalog.application.LiveTaxAuditService.Audit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Products", description = "Product builder, listing moderation and lookup")
@RestController
class TaxGateAuditController {

    private final LiveTaxAuditService audit;

    TaxGateAuditController(LiveTaxAuditService audit) {
        this.audit = audit;
    }

    @Schema(name = "LiveWithoutTaxDecisionPage", description = "Live listings that have a variant without a stored, "
            + "resolved tax decision. Must always be empty.")
    record LiveWithoutTaxDecisionPage(List<LiveWithoutTaxDecisionItem> items, long total, int page, int size) {

        static LiveWithoutTaxDecisionPage of(Audit a) {
            return new LiveWithoutTaxDecisionPage(a.items().stream().map(LiveWithoutTaxDecisionItem::of).toList(),
                    a.total(), a.page(), a.size());
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
                    + "gate is not READY or that has no stored decision. Always empty when the publish gate holds; "
                    + "the sweep closes anything listed here within a minute.")
    @GetMapping("/v1/tax-gate/live-without-decision")
    LiveWithoutTaxDecisionPage liveWithoutDecision(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "25") int size) {
        return LiveWithoutTaxDecisionPage.of(audit.liveWithoutTaxDecision(page, size));
    }
}
