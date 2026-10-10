package com.afrotransact.payments.adapter.in.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.application.SupplierPaymentService;
import com.afrotransact.payments.domain.SupplierPayment;
import com.afrotransact.payments.domain.SupplierPaymentStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AfroTransact Retail supplier payments (S226): what Retail owes its suppliers and Finance's payment run. */
@Tag(name = "Supplier payments", description = "AfroTransact Retail (merchant of record) supplier payables released by "
        + "retail-service after the three-way match, scheduled by payment terms and paid by Finance from the operating "
        + "bank account (never through Stripe Connect)")
@RestController
class SupplierPaymentController {

    private final SupplierPaymentService payments;

    SupplierPaymentController(SupplierPaymentService payments) {
        this.payments = payments;
    }

    @Schema(name = "SupplierPaymentInvoice")
    record InvoiceResponse(UUID invoiceId, String invoiceNumber, long amountMinor, String currency) {
    }

    @Schema(name = "SupplierPayment")
    record SupplierPaymentResponse(UUID id, UUID poId, String poNumber, UUID supplierId, String supplierCode,
                                   long amountMinor, String currency, List<InvoiceResponse> invoices,
                                   @Schema(allowableValues = {"SCHEDULED", "ON_HOLD", "PAID"}) String status,
                                   @Schema(description = "Payment date by the supplier's terms") LocalDate dueOn,
                                   @Schema(description = "Scheduled and due on or before today") boolean due,
                                   String paymentReference, String holdReason, String paidBy, Instant scheduledAt,
                                   Instant paidAt, UUID payableJournalId, UUID paymentJournalId) {

        static SupplierPaymentResponse of(SupplierPayment p) {
            return new SupplierPaymentResponse(p.id(), p.poId(), p.poNumber(), p.supplierId(), p.supplierCode(),
                    p.amount().minor(), p.amount().currency(), p.invoices().stream().map(i -> new InvoiceResponse(
                            i.invoiceId(), i.invoiceNumber(), i.amount().minor(), i.amount().currency())).toList(),
                    p.status().name(), p.dueOn(), p.dueBy(LocalDate.now()), p.paymentReference(), p.holdReason(),
                    p.paidBy(), p.scheduledAt(), p.paidAt(), p.payableJournal(), p.paymentJournal());
        }
    }

    record MarkPaidRequest(@NotBlank @Size(max = 120) @Schema(description = "Bank transfer / ACH reference")
                           String paymentReference,
                           @Schema(description = "When the transfer was made (default now)") Instant paidAt) {
    }

    record SupplierHoldRequest(@NotBlank @Size(max = 500) String reason) {
    }

    @Operation(summary = "AfroTransact Retail supplier payments (payments:read, platform-wide staff)",
            description = "Newest due date first. dueBy lists the payment run (SCHEDULED, due on or before the day).")
    @GetMapping("/v1/supplier-payments")
    List<SupplierPaymentResponse> listSupplierPayments(@RequestParam(required = false) SupplierPaymentStatus status,
                                       @RequestParam(required = false) UUID supplierId,
                                       @RequestParam(required = false) LocalDate dueBy,
                                                       @RequestParam(defaultValue = "200") int limit) {
        return payments.list(status, supplierId, dueBy, limit).stream().map(SupplierPaymentResponse::of).toList();
    }

    @Operation(summary = "One supplier payment (payments:read)")
    @GetMapping("/v1/supplier-payments/{id}")
    SupplierPaymentResponse getSupplierPayment(@PathVariable UUID id) {
        return SupplierPaymentResponse.of(payments.get(id));
    }

    @Operation(summary = "Record that Finance paid the supplier (payouts:approve)",
            description = "Posts Dr 2500:<supplier> / Cr 1050 Operating bank. Repeating the same reference is a no-op; "
                    + "a held payment must be released first (409 payments.supplier-payment-on-hold).")
    @PostMapping("/v1/supplier-payments/{id}/paid")
    SupplierPaymentResponse markSupplierPaymentPaid(@PathVariable UUID id, @Valid @RequestBody MarkPaidRequest body) {
        return SupplierPaymentResponse.of(payments.markPaid(id, body.paymentReference(), body.paidAt()));
    }

    @Operation(summary = "Hold a supplier payment (payouts:approve)")
    @PostMapping("/v1/supplier-payments/{id}/hold")
    SupplierPaymentResponse holdSupplierPayment(@PathVariable UUID id, @Valid @RequestBody SupplierHoldRequest body) {
        return SupplierPaymentResponse.of(payments.hold(id, body.reason()));
    }

    @Operation(summary = "Release a held supplier payment back to the schedule (payouts:approve)")
    @PostMapping("/v1/supplier-payments/{id}/release")
    SupplierPaymentResponse releaseSupplierPayment(@PathVariable UUID id) {
        return SupplierPaymentResponse.of(payments.release(id));
    }
}
