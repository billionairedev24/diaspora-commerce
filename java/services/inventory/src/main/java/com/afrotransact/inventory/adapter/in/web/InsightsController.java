package com.afrotransact.inventory.adapter.in.web;

import java.util.UUID;

import com.afrotransact.inventory.adapter.in.web.InboundDtos.FbaInventoryResponse;
import com.afrotransact.inventory.adapter.in.web.InboundDtos.LotTraceResponse;
import com.afrotransact.inventory.application.InventoryInsightsService;
import com.afrotransact.inventory.port.InventoryInsightsRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Insights", description = "Seller FBA inventory (S067) and the staff inventory hub overview and recall "
        + "traces (S066); inventory:read, sellers see their own organisation")
@RestController
class InsightsController {

    private final InventoryInsightsService insights;

    InsightsController(InventoryInsightsService insights) {
        this.insights = insights;
    }

    @Operation(operationId = "fbaInventory", summary = "A seller's stock in AfroTransact fulfilment centres",
            description = "Per SKU × FC: on hand, reserved, available, inbound, unfulfillable (quarantined, damaged, "
                    + "expired), ageing since receipt (D0_90 … D365_PLUS), storage volume and whether the stock is "
                    + "stranded (no live Fulfilled-by-AfroTransact offer: NO_OFFER / OFFER_INACTIVE), with totals "
                    + "over all of the owner's FBA stock. Sellers see their own; staff name ownerOrg.")
    @GetMapping("/v1/fba/inventory")
    FbaInventoryResponse fba(@RequestParam(required = false) String ownerOrg,
                             @RequestParam(required = false) String nodeId,
                             @RequestParam(required = false) String sku,
                             @RequestParam(defaultValue = "false") boolean stranded,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "50") int size) {
        return FbaInventoryResponse.of(insights.fbaInventory(ownerOrg, nodeId, sku, stranded, page, size));
    }

    @Operation(operationId = "inventoryOverview", summary = "Inventory hub overview",
            description = "Units and landed value per lot status, expiry buckets (node-local days), per-node "
                    + "figures, open inbound shipments, held reservations and alerts (expired and expiring lots, "
                    + "low stock, inbound discrepancies).")
    @GetMapping("/v1/overview")
    InventoryInsightsRepository.Overview overview(@RequestParam(required = false) String nodeId,
                                                  @RequestParam(required = false) String ownerOrg) {
        return insights.overview(nodeId, ownerOrg);
    }

    @Operation(operationId = "lotTrace", summary = "Recall trace of a lot",
            description = "The lot, its ledger rows, the same batch at other nodes (transfers, -DMG) and the orders "
                    + "whose reservations took its units.")
    @GetMapping("/v1/lots/{id}/trace")
    LotTraceResponse trace(@PathVariable UUID id) {
        return LotTraceResponse.of(insights.lotTrace(id));
    }
}
