package com.afrotransact.retail.adapter.in.web;

import java.util.UUID;

import com.afrotransact.retail.adapter.in.web.InvoiceDtos.InvoiceResponse;
import com.afrotransact.retail.adapter.in.web.InvoiceDtos.RegisterInvoiceRequest;
import com.afrotransact.retail.adapter.in.web.PurchaseOrderDtos.NoteRequest;
import com.afrotransact.retail.application.InvoiceMatchService;
import com.afrotransact.retail.domain.InvoiceStatus;
import com.afrotransact.retail.domain.SupplierInvoice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Supplier invoices", description = "Three-way match: PO price × received quantity vs supplier invoice "
        + "(±1%) → MATCHED, EXCEPTION (routed to the buyer) or ON_HOLD (S074). retail:finance")
@RestController
@RequestMapping("/v1/invoices")
class InvoiceController {

    private final InvoiceMatchService invoices;

    InvoiceController(InvoiceMatchService invoices) {
        this.invoices = invoices;
    }

    @Operation(operationId = "registerInvoice", summary = "Register and match a supplier invoice; matched invoices post Dr 2110 · Cr 2100 (+PPV/FX)")
    @PostMapping
    ResponseEntity<InvoiceResponse> register(@RequestHeader(Versions.IDEMPOTENCY_KEY) @NotBlank @Size(max = 160)
                                             String idempotencyKey,
                                             @Valid @RequestBody RegisterInvoiceRequest body) {
        SupplierInvoice i = invoices.register(body.toCommand(), idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(InvoiceResponse.of(i));
    }

    @Operation(operationId = "listInvoices", summary = "List invoices, e.g. ?status=EXCEPTION for the buyer's variance queue")
    @GetMapping
    PageResponse<InvoiceResponse> list(@RequestParam(required = false) InvoiceStatus status,
                                       @RequestParam(required = false) UUID poId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(invoices.list(status, poId, page, size), InvoiceResponse::of);
    }

    @Operation(operationId = "getInvoice", summary = "An invoice with its match result")
    @GetMapping("/{id}")
    InvoiceResponse get(@PathVariable UUID id) {
        return InvoiceResponse.of(invoices.get(id));
    }

    @Operation(operationId = "rematchInvoice", summary = "Re-run the match (after receipts or claim settlement)")
    @PostMapping("/{id}/rematch")
    InvoiceResponse rematch(@PathVariable UUID id) {
        return InvoiceResponse.of(invoices.rematch(id));
    }

    @Operation(operationId = "approveInvoiceException", summary = "Accept an exception with a note; the invoice becomes payable")
    @PostMapping("/{id}/approve")
    InvoiceResponse approve(@PathVariable UUID id, @Valid @RequestBody NoteRequest body) {
        return InvoiceResponse.of(invoices.approveException(id, body.note()));
    }

    @Operation(operationId = "rejectInvoice", summary = "Reject (dispute) an exception or held invoice with a note")
    @PostMapping("/{id}/reject")
    InvoiceResponse reject(@PathVariable UUID id, @Valid @RequestBody NoteRequest body) {
        return InvoiceResponse.of(invoices.reject(id, body.note()));
    }
}
