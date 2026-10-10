package com.afrotransact.inventory.adapter.in.web;

import com.afrotransact.inventory.adapter.in.web.InventoryDtos.InboundRequest;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.PositionResponse;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.ReceiptRequest;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.ReceiptResponse;
import com.afrotransact.inventory.application.InboundCommand;
import com.afrotransact.inventory.application.ReceiptResult;
import com.afrotransact.inventory.application.ReceivingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Receiving", description = "Goods receipts from retail-service and FBA inbound (inventory:receive)")
@RestController
class ReceiptController {

    private final ReceivingService receiving;

    ReceiptController(ReceivingService receiving) {
        this.receiving = receiving;
    }

    @Operation(summary = "Receive the lots of a completed goods receipt",
            description = "Creates one lot per batch (RECEIVE + PUT_AWAY). Damaged units become a separate <lot>-DMG "
                    + "quarantine lot; lots with remaining life below max(90 days, 50% of shelf life) are "
                    + "quarantined; expired lots or impossible dates reject the receipt (422 inventory.lot-rejected).")
    @PostMapping("/v1/receipts")
    ResponseEntity<ReceiptResponse> receive(
            @RequestHeader(ReservationController.IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
            @Valid @RequestBody ReceiptRequest body) {
        ReceiptResult result = receiving.receive(body.toCommand(), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ReceiptResponse.of(result));
    }

    @Operation(summary = "Record expected inbound units (ASN); negative qty cancels")
    @PostMapping("/v1/inbound")
    PositionResponse inbound(@Valid @RequestBody InboundRequest body) {
        return PositionResponse.of(receiving.expectInbound(new InboundCommand(body.sku(), body.nodeId(),
                body.ownerOrg(), body.qty(), body.reference())));
    }
}
