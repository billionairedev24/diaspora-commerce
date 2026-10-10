package com.afrotransact.retail.domain;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * One counted batch at the dock: expected units (ASN or open PO quantity), the batch actually received (lot, made
 * and expiry dates), good and damaged counts, put-away bin and QA result. Split lines carry an extra batch of the
 * same PO line with expected 0.
 */
public record ReceiptLine(int lineNo, int poLineNo, Integer asnLineNo, UUID itemId, String sku, int expectedQty,
                          String asnLotNo, String lotNo, LocalDate mfgDate, LocalDate expDate, int qtyGood,
                          int qtyDamaged, String bin, QaResult qa, boolean scanned, boolean split) {

    public ReceiptLine {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(sku, "sku");
        if (qtyGood < 0 || qtyDamaged < 0 || expectedQty < 0) {
            throw new RetailRuleViolation("retail.receipt-invalid", "Line " + lineNo + ": counts cannot be negative");
        }
        lotNo = lotNo == null || lotNo.isBlank() ? null : lotNo.trim().toUpperCase(Locale.ROOT);
        bin = bin == null || bin.isBlank() ? null : bin.trim().toUpperCase(Locale.ROOT);
        qa = qa == null ? QaResult.PENDING : qa;
    }

    public int received() {
        return qtyGood + qtyDamaged;
    }
}
