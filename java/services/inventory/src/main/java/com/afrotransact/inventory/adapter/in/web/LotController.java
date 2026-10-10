package com.afrotransact.inventory.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.inventory.adapter.in.web.InventoryDtos.CycleCountRequest;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.LotPage;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.LotResponse;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.ReasonRequest;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.TransferRequest;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.TransferResponse;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.WriteOffRequest;
import com.afrotransact.inventory.application.LotLifecycleService;
import com.afrotransact.inventory.application.LotService;
import com.afrotransact.inventory.domain.LotStatus;
import com.afrotransact.inventory.domain.MarkdownFlag;
import com.afrotransact.inventory.port.LotRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Lots", description = "Batches with expiry, FEFO order, quarantine, write-off, cycle counts, transfers")
@RestController
@RequestMapping("/v1/lots")
class LotController {

    private final LotService lots;
    private final LotLifecycleService lifecycle;

    LotController(LotService lots, LotLifecycleService lifecycle) {
        this.lots = lots;
        this.lifecycle = lifecycle;
    }

    @Operation(summary = "Search lots (inventory:read; sellers see their own), FEFO order within SKU × node")
    @GetMapping
    LotPage search(@RequestParam(required = false) String sku,
                   @RequestParam(required = false) String nodeId,
                   @RequestParam(required = false) String ownerOrg,
                   @RequestParam(required = false) LotStatus status,
                   @RequestParam(required = false) String lotNo,
                   @RequestParam(defaultValue = "0") int page,
                   @RequestParam(defaultValue = "50") int size) {
        return LotPage.of(lots.search(new LotRepository.LotQuery(sku, nodeId, ownerOrg, status, lotNo, page, size)));
    }

    @Operation(summary = "Markdown list: sellable lots expiring within 60 (or 30) days, soonest first")
    @GetMapping("/markdown")
    List<LotResponse> markdown(@Parameter(description = "WITHIN_60 (default, includes ≤ 30) or WITHIN_30")
                               @RequestParam(required = false) MarkdownFlag within) {
        return lots.markdown(within).stream().map(LotResponse::of).toList();
    }

    @Operation(summary = "Get a lot")
    @GetMapping("/{id}")
    LotResponse get(@PathVariable UUID id) {
        return LotResponse.of(lots.get(id));
    }

    @Operation(summary = "Quarantine a sellable lot with a reason (inventory:adjust)",
            description = "Blocks the lot from sale; refused while units are reserved.")
    @PostMapping("/{id}/quarantine")
    LotResponse quarantine(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return LotResponse.of(lots.quarantine(id, body.reason()));
    }

    @Operation(summary = "Release a quarantined or damaged lot back to sale after inspection (inventory:adjust)")
    @PostMapping("/{id}/release")
    LotResponse release(@PathVariable UUID id, @Valid @RequestBody ReasonRequest body) {
        return LotResponse.of(lots.release(id, body.reason()));
    }

    @Operation(summary = "Write off units of a lot with a reason (inventory:adjust)",
            description = "Never touches reserved units. The lot becomes WRITTEN_OFF when nothing is left.")
    @PostMapping("/{id}/write-off")
    LotResponse writeOff(@PathVariable UUID id,
                         @RequestHeader(name = ReservationController.IDEMPOTENCY_KEY, required = false)
                         String idempotencyKey,
                         @Valid @RequestBody WriteOffRequest body) {
        return LotResponse.of(lots.writeOff(id, body.qty(), body.reason(), idempotencyKey));
    }

    @Operation(summary = "Cycle count: post the counted quantity with a reason (ADJUST, inventory:adjust)",
            description = "Refused (422 inventory.below-reserved) when it would take on hand below reserved.")
    @PostMapping("/{id}/adjust")
    LotResponse adjust(@PathVariable UUID id,
                       @RequestHeader(name = ReservationController.IDEMPOTENCY_KEY, required = false)
                       String idempotencyKey,
                       @Valid @RequestBody CycleCountRequest body) {
        return LotResponse.of(lots.adjust(id, body.countedQty(), body.reason(), idempotencyKey));
    }

    @Operation(summary = "Transfer unreserved units of a lot to another node (TRANSFER, inventory:adjust)")
    @PostMapping("/{id}/transfer")
    TransferResponse transfer(@PathVariable UUID id,
                              @RequestHeader(name = ReservationController.IDEMPOTENCY_KEY, required = false)
                              String idempotencyKey,
                              @Valid @RequestBody TransferRequest body) {
        LotService.TransferResult r = lots.transfer(id, new LotService.TransferCommand(body.toNodeId(), body.toBin(),
                body.qty(), body.reason()), idempotencyKey);
        return new TransferResponse(r.reference(), LotResponse.of(r.from()), LotResponse.of(r.to()));
    }

    @Operation(summary = "Run the lot expiry job now (inventory:adjust)")
    @PostMapping("/expiry-runs")
    LotLifecycleService.ExpiryRun expireNow() {
        return lifecycle.expireNow();
    }

    @Operation(summary = "Recompute markdown flags now (inventory:adjust); returns lots whose flag changed")
    @PostMapping("/markdown-runs")
    int markdownNow() {
        return lifecycle.markdownNow();
    }
}
