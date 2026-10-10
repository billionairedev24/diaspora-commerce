package com.afrotransact.retail.domain;

/** Supplier invoice lifecycle driven by the three-way match. */
public enum InvoiceStatus {
    MATCHED,
    EXCEPTION,
    ON_HOLD,
    APPROVED,
    REJECTED,
    PAYMENT_RELEASED;

    /** Matched, or an exception Finance approved: the invoice counts against the PO and can be paid. */
    public boolean payable() {
        return this == MATCHED || this == APPROVED;
    }
}
