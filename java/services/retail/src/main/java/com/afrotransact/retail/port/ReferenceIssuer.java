package com.afrotransact.retail.port;

import java.time.LocalDate;

/** Human-readable document numbers from database sequences (gap-tolerant, never reused). */
public interface ReferenceIssuer {

    /** {@code PO-1P-323}. */
    String nextPurchaseOrderNumber();

    /** {@code SHP-2610-GH06}. */
    String nextShipmentReference(String originCountry, LocalDate date);

    /** {@code GRN-2610-0042}. */
    String nextReceiptNumber(LocalDate date);

    /** {@code JE-24118}. */
    String nextJournalNumber();

    /** Six-digit item reference under the GS1 company prefix. */
    long nextItemReference();
}
