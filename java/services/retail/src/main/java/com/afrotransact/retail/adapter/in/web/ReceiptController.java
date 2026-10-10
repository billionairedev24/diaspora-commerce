package com.afrotransact.retail.adapter.in.web;

import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.adapter.in.web.ReceiptDtos.ClaimResolutionRequest;
import com.afrotransact.retail.adapter.in.web.ReceiptDtos.CountRequest;
import com.afrotransact.retail.adapter.in.web.ReceiptDtos.OpenReceiptRequest;
import com.afrotransact.retail.adapter.in.web.ReceiptDtos.ReceiptResponse;
import com.afrotransact.retail.adapter.in.web.ReceiptDtos.ValidationResponse;
import com.afrotransact.retail.adapter.in.web.ShipmentDtos.ChargesRequest;
import com.afrotransact.retail.application.ReceivingService;
import com.afrotransact.retail.domain.GoodsReceipt;
import com.afrotransact.retail.domain.ReceiptStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Goods receipts", description = "Count, validate and complete receipts; lots are posted to inventory "
        + "with landed cost, discrepancies raise claims and the GL entry is produced (S072/S073)")
@RestController
@RequestMapping("/v1/receipts")
class ReceiptController {

    private final ReceivingService receiving;

    ReceiptController(ReceivingService receiving) {
        this.receiving = receiving;
    }

    @Operation(operationId = "openReceipt", summary = "Open a receipt at the dock (retail:receive); ASN lines are pre-filled")
    @PostMapping
    ResponseEntity<ReceiptResponse> open(@Valid @RequestBody OpenReceiptRequest body) {
        GoodsReceipt r = receiving.open(body.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).eTag("\"" + r.version() + "\"").body(ReceiptResponse.of(r));
    }

    @Operation(operationId = "listReceipts", summary = "List receipts (retail:read)")
    @GetMapping
    PageResponse<ReceiptResponse> list(@RequestParam(required = false) ReceiptStatus status,
                                       @RequestParam(required = false) UUID poId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(receiving.list(status, poId, page, size), ReceiptResponse::of);
    }

    @Operation(operationId = "getReceipt", summary = "A receipt (retail:read)")
    @GetMapping("/{id}")
    ResponseEntity<ReceiptResponse> get(@PathVariable UUID id) {
        return tagged(receiving.get(id));
    }

    @Operation(operationId = "countReceiptLines", summary = "Record counted batches: lot, made/expiry, good/damaged, bin, QA (retail:receive)")
    @PutMapping("/{id}/lines")
    ResponseEntity<ReceiptResponse> count(@PathVariable UUID id, @Valid @RequestBody CountRequest body,
                                          @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(receiving.count(id, body.toInputs(), Versions.expected(ifMatch)));
    }

    @Operation(operationId = "updateReceiptCharges", summary = "Landed-cost charges of a receipt without a shipment (retail:finance)")
    @PutMapping("/{id}/charges")
    ResponseEntity<ReceiptResponse> charges(@PathVariable UUID id, @Valid @RequestBody ChargesRequest body,
                                            @RequestHeader(name = Versions.IF_MATCH, required = false) String ifMatch) {
        return tagged(receiving.updateCharges(id, body.charges() == null ? List.of()
                : body.charges().stream().map(ChargeDto::toDomain).toList(), Versions.expected(ifMatch)));
    }

    @Operation(operationId = "approveOverReceipt", summary = "Allow more than the 2% over-receipt tolerance (retail:po:approve)")
    @PostMapping("/{id}/approve-over-receipt")
    ResponseEntity<ReceiptResponse> approveOver(@PathVariable UUID id,
                                                @RequestHeader(name = Versions.IF_MATCH, required = false)
                                                String ifMatch) {
        return tagged(receiving.approveOverReceipt(id, Versions.expected(ifMatch)));
    }

    @Operation(operationId = "validateReceipt", summary = "Validate without completing: errors, warnings, variances (retail:receive)")
    @PostMapping("/{id}/validate")
    ValidationResponse validate(@PathVariable UUID id) {
        return ValidationResponse.of(receiving.validate(id));
    }

    @Operation(operationId = "completeReceipt", summary = "Complete the receipt (retail:receive)",
            description = "Validates, allocates landed cost, posts lots to inventory (Idempotency-Key retail-<id>), "
                    + "raises discrepancies and claims, updates the PO, shipment and supplier scorecard and posts "
                    + "Dr 1300/1320 · Cr 1310(2110)/2120. Safe to retry; 503 leaves the receipt POSTING.")
    @PostMapping("/{id}/complete")
    ResponseEntity<ReceiptResponse> complete(@PathVariable UUID id,
                                             @RequestHeader(name = Versions.IDEMPOTENCY_KEY, required = false)
                                             @Size(max = 160) String idempotencyKey) {
        return tagged(receiving.complete(id));
    }

    @Operation(operationId = "resolveReceiptClaim", summary = "Settle a shortage or damage claim (retail:po:write); open claims hold supplier payment")
    @PostMapping("/{id}/claims/{claimRef}/resolve")
    ResponseEntity<ReceiptResponse> resolve(@PathVariable UUID id, @PathVariable String claimRef,
                                            @Valid @RequestBody ClaimResolutionRequest body) {
        return tagged(receiving.resolveClaim(id, claimRef, body.resolution()));
    }

    private static ResponseEntity<ReceiptResponse> tagged(GoodsReceipt r) {
        return Versions.tagged(ReceiptResponse.of(r), r.version());
    }
}
