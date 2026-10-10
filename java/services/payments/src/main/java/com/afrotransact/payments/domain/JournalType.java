package com.afrotransact.payments.domain;

/** Business event a journal records. */
public enum JournalType {
    CAPTURE,
    REFUND,
    REFUND_SETTLED,
    TRANSFER,
    TRANSFER_REVERSAL,
    DISPUTE_OPENED,
    DISPUTE_WON,
    DISPUTE_LOST,
    PROVIDER_FEE,
    PROVIDER_FEE_COLLECTED,
    LABEL_CHARGE,
    LABEL_CREDIT,
    LABEL_RECOVERY,
    /** AfroTransact Retail supplier invoices released for payment (retail.payment.released, S226). */
    SUPPLIER_PAYABLE,
    /** A released supplier payment paid from the operating bank account. */
    SUPPLIER_PAYMENT
}
