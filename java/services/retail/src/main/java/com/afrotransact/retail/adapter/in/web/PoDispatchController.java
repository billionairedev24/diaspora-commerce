package com.afrotransact.retail.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.application.PoDispatchService;
import com.afrotransact.retail.domain.PoDispatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Purchase order delivery", description = "The PO PDF and the delivery of sent POs to suppliers: email "
        + "with a signed link to the PDF (media + notifications) and EDI 850 for EDI-enabled suppliers (S225).")
@RestController
@RequestMapping("/v1/purchase-orders/{id}")
class PoDispatchController {

    private final PoDispatchService dispatch;

    PoDispatchController(PoDispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @Schema(name = "PoDispatch", description = "Delivery of a sent PO on one channel")
    record DispatchResponse(UUID id, String channel,
                            @Schema(description = "PENDING, DONE or FAILED") String status, int attempts,
                            Instant nextAttemptAt, String lastError,
                            @Schema(description = "Contact email (EMAIL) or ISA interchange id (EDI)") String recipient,
                            @Schema(description = "Media asset of the PO PDF (EMAIL)") UUID assetId,
                            Instant linkExpiresAt, String controlNumber, String transmissionId, Instant updatedAt) {

        static DispatchResponse of(PoDispatch d) {
            return new DispatchResponse(d.id(), d.channel().name(), d.status().name(), d.attempts(),
                    d.nextAttemptAt(), d.lastError(), d.recipient(), d.assetId(), d.linkExpiresAt(),
                    d.controlNumber(), d.transmissionId(), d.updatedAt());
        }
    }

    @Operation(operationId = "getPurchaseOrderPdf", summary = "The PO as PDF: lines, terms and totals (approved POs)")
    @GetMapping(path = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        byte[] pdf = dispatch.pdf(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("purchase-order-" + id
                        + ".pdf").build().toString())
                .body(pdf);
    }

    @Operation(operationId = "listPurchaseOrderDispatches", summary = "Deliveries of a sent PO (retail:read)")
    @GetMapping("/dispatches")
    List<DispatchResponse> dispatches(@PathVariable UUID id) {
        return dispatch.dispatches(id).stream().map(DispatchResponse::of).toList();
    }

    @Operation(operationId = "retryPurchaseOrderDispatches",
            summary = "Retry the PO's failed deliveries (retail:po:write)",
            description = "After fixing the supplier contact or EDI settings; the email goes to the current contact.")
    @PostMapping("/dispatches/retry")
    List<DispatchResponse> retry(@PathVariable UUID id) {
        return dispatch.retryFailed(id).stream().map(DispatchResponse::of).toList();
    }
}
