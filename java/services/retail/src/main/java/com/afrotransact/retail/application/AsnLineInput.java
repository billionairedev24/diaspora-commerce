package com.afrotransact.retail.application;

import java.time.LocalDate;

/** One packing-list line: units of a PO line with the supplier's batch details (may be filled in later). */
public record AsnLineInput(int poLineNo, int qty, String lotNo, LocalDate mfgDate, LocalDate expDate) {
}
