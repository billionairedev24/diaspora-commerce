package com.afrotransact.inventory.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.inventory.adapter.in.web.InventoryDtos.MovementPage;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.PositionPage;
import com.afrotransact.inventory.application.Availability;
import com.afrotransact.inventory.application.StockQueryService;
import com.afrotransact.inventory.domain.MovementType;
import com.afrotransact.inventory.port.LedgerRepository;
import com.afrotransact.inventory.port.PositionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stock", description = "Storefront availability, positions and the stock ledger")
@RestController
class StockController {

    private final StockQueryService stock;

    StockController(StockQueryService stock) {
        this.stock = stock;
    }

    @Operation(summary = "Sellable availability per SKU (summed over nodes) and per node",
            description = "Public: storefront, search, cart and Lola read it. available = on hand − reserved.")
    @SecurityRequirements
    @GetMapping("/v1/availability")
    List<Availability> availability(
            @Parameter(description = "Comma-separated SKUs (max 100)", example = "AT-JOL-6,AT-BAS-10")
            @RequestParam List<String> skus) {
        return stock.availability(skus);
    }

    @Operation(summary = "Stock positions (inventory:read; sellers see only their own FBA stock)")
    @GetMapping("/v1/positions")
    PositionPage positions(@RequestParam(required = false) String sku,
                           @RequestParam(required = false) String nodeId,
                           @RequestParam(required = false) String ownerOrg,
                           @Parameter(description = "Only positions with 10 or fewer available")
                           @RequestParam(defaultValue = "false") boolean lowStock,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "50") int size) {
        return PositionPage.of(stock.positions(new PositionRepository.PositionQuery(sku, nodeId, ownerOrg, lowStock,
                page, size)));
    }

    @Operation(summary = "Stock movement history, newest first (inventory:read)",
            description = "Filter by lot for recalls, or by reference (order id, receipt, transfer).")
    @GetMapping("/v1/movements")
    MovementPage movements(@RequestParam(required = false) String sku,
                           @RequestParam(required = false) String nodeId,
                           @RequestParam(required = false) UUID lotId,
                           @RequestParam(required = false) String reference,
                           @RequestParam(required = false) String ownerOrg,
                           @RequestParam(required = false) MovementType type,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "100") int size) {
        return MovementPage.of(stock.movements(new LedgerRepository.MovementQuery(sku, nodeId, lotId, reference,
                ownerOrg, type, page, size)));
    }
}
