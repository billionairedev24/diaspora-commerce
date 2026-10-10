package com.afrotransact.retail.application;

import java.time.LocalDate;

import com.afrotransact.retail.domain.QaResult;

/**
 * A counted batch at the dock. Use an existing line number to record counts, or a new line number with the PO line
 * to split a mixed batch.
 */
public record ReceiptLineInput(int lineNo, int poLineNo, String lotNo, LocalDate mfgDate, LocalDate expDate,
                               int qtyGood, int qtyDamaged, String bin, QaResult qa, boolean scanned) {
}
