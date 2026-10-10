package com.afrotransact.retail.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** A supplier invoice against one PO, with the FX rate at the invoice date and its latest match result. */
public record SupplierInvoice(UUID id, UUID supplierId, UUID poId, String poNumber, String invoiceNumber,
                              LocalDate invoiceDate, String currency, FxRate fx, List<InvoiceLine> lines,
                              InvoiceStatus status, MatchResult match, String resolutionNote, String resolvedBy,
                              UUID journalEntryId, String receivedBy, Instant createdAt, Instant updatedAt,
                              long version) {

    public SupplierInvoice {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(poId, "poId");
        Objects.requireNonNull(invoiceDate, "invoiceDate");
        Objects.requireNonNull(fx, "fx");
        if (invoiceNumber == null || invoiceNumber.isBlank() || invoiceNumber.length() > 60) {
            throw new RetailRuleViolation("retail.invoice-invalid", "Supplier invoice number is required");
        }
        currency = Currencies.require(currency);
        lines = lines == null ? List.of() : List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new RetailRuleViolation("retail.invoice-invalid", "An invoice needs at least one line");
        }
    }

    public static SupplierInvoice register(UUID id, PurchaseOrder po, String invoiceNumber, LocalDate invoiceDate,
                                           String currency, FxRate fx, List<InvoiceLine> lines, MatchResult match,
                                           String by, Instant now) {
        return new SupplierInvoice(id, po.supplierId(), po.id(), po.number(), invoiceNumber.trim(), invoiceDate,
                currency, fx, lines, statusFor(match.outcome()), match, null, null, null, by, now, now, 0);
    }

    public long totalMinor() {
        long total = 0;
        for (InvoiceLine l : lines) {
            total = Math.addExact(total, l.amountMinor());
        }
        return total;
    }

    public Map<Integer, Integer> qtyByPoLine() {
        return lines.stream().collect(Collectors.toMap(InvoiceLine::poLineNo, InvoiceLine::qty, Integer::sum));
    }

    public SupplierInvoice rematch(MatchResult result, Instant now) {
        if (status != InvoiceStatus.EXCEPTION && status != InvoiceStatus.ON_HOLD) {
            throw new RetailStateConflict("retail.invoice-invalid-transition", "Invoice " + invoiceNumber + " is "
                    + status + "; only exceptions and held invoices are re-matched");
        }
        return with(statusFor(result.outcome()), result, resolutionNote, resolvedBy, journalEntryId, now);
    }

    /** Finance accepts the variance; the invoice becomes payable. */
    public SupplierInvoice approveException(String by, String note, Instant now) {
        if (status != InvoiceStatus.EXCEPTION) {
            throw new RetailStateConflict("retail.invoice-invalid-transition", "Invoice " + invoiceNumber + " is "
                    + status + "; only exceptions can be approved");
        }
        requireNote(note);
        return with(InvoiceStatus.APPROVED, match, note, by, journalEntryId, now);
    }

    public SupplierInvoice reject(String by, String note, Instant now) {
        if (status != InvoiceStatus.EXCEPTION && status != InvoiceStatus.ON_HOLD) {
            throw new RetailStateConflict("retail.invoice-invalid-transition", "Invoice " + invoiceNumber + " is "
                    + status);
        }
        requireNote(note);
        return with(InvoiceStatus.REJECTED, match, note, by, journalEntryId, now);
    }

    public SupplierInvoice posted(UUID journalId, Instant now) {
        return with(status, match, resolutionNote, resolvedBy, journalId, now);
    }

    public SupplierInvoice releasePayment(Instant now) {
        if (!status.payable()) {
            throw new RetailStateConflict("retail.invoice-not-payable", "Invoice " + invoiceNumber + " is " + status);
        }
        return with(InvoiceStatus.PAYMENT_RELEASED, match, resolutionNote, resolvedBy, journalEntryId, now);
    }

    private static InvoiceStatus statusFor(MatchOutcome outcome) {
        return switch (outcome) {
            case MATCH -> InvoiceStatus.MATCHED;
            case EXCEPTION -> InvoiceStatus.EXCEPTION;
            case HOLD -> InvoiceStatus.ON_HOLD;
        };
    }

    private static void requireNote(String note) {
        if (note == null || note.isBlank()) {
            throw new RetailRuleViolation("retail.reason-required", "A note is required");
        }
    }

    private SupplierInvoice with(InvoiceStatus s, MatchResult m, String note, String by, UUID journal, Instant now) {
        return new SupplierInvoice(id, supplierId, poId, poNumber, invoiceNumber, invoiceDate, currency, fx, lines, s,
                m, note, by, journal, receivedBy, createdAt, now, version);
    }
}
