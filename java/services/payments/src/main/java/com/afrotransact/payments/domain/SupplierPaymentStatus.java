package com.afrotransact.payments.domain;

/** State of an AfroTransact Retail supplier payment. */
public enum SupplierPaymentStatus {
    /** Released by retail-service and booked (Dr 1400 / Cr 2500:supplier); waiting for its due date and Finance. */
    SCHEDULED,
    /** Finance stopped it (dispute with the supplier, bank details being checked); still owed. */
    ON_HOLD,
    /** Paid from the operating bank account (Dr 2500:supplier / Cr 1050). */
    PAID
}
