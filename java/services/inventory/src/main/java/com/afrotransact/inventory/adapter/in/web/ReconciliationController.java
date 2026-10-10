package com.afrotransact.inventory.adapter.in.web;

import com.afrotransact.inventory.adapter.in.web.InventoryDtos.ReconciliationResponse;
import com.afrotransact.inventory.application.ReconciliationService;
import com.afrotransact.platform.web.error.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Reconciliation", description = "Positions recomputed from the ledger and lots; drift alerts")
@RestController
@RequestMapping("/v1/reconciliation/runs")
class ReconciliationController {

    private final ReconciliationService reconciliation;

    ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Operation(summary = "Run reconciliation now (inventory:adjust)")
    @PostMapping
    ReconciliationResponse run() {
        return ReconciliationResponse.of(reconciliation.runNow());
    }

    @Operation(summary = "Latest reconciliation run (staff, inventory:read)")
    @GetMapping("/latest")
    ReconciliationResponse latest() {
        return ReconciliationResponse.of(reconciliation.latest().orElseThrow(() -> new NotFoundException(
                "inventory.no-reconciliation", "No reconciliation has run yet")));
    }
}
