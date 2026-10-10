package com.afrotransact.inventory.adapter.in.web;

import com.afrotransact.inventory.adapter.in.web.InventoryDtos.ReturnRequest;
import com.afrotransact.inventory.adapter.in.web.InventoryDtos.ReturnResponse;
import com.afrotransact.inventory.application.ReturnCommand;
import com.afrotransact.inventory.application.ReturnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Returns", description = "Returned units after inspection (inventory:adjust)")
@RestController
class ReturnController {

    private final ReturnService returns;

    ReturnController(ReturnService returns) {
        this.returns = returns;
    }

    @Operation(summary = "Restock a returned unit or move it to the lot's -DMG lot",
            description = "RETURN_RESTOCK puts units back into their sellable lot; RETURN_DAMAGED holds them in "
                    + "<lot>-DMG. Never more than the order shipped.")
    @PostMapping("/v1/returns")
    ReturnResponse accept(
            @RequestHeader(ReservationController.IDEMPOTENCY_KEY) @NotBlank @Size(max = 160) String idempotencyKey,
            @Valid @RequestBody ReturnRequest body) {
        return ReturnResponse.of(returns.accept(new ReturnCommand(body.orderId(), body.sku(), body.lotId(), body.qty(),
                body.condition(), body.reason()), idempotencyKey));
    }
}
