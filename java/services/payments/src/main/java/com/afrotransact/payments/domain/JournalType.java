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
    /** A seller plan invoice withheld from earnings (S298): Dr 2000:org (no order) / Cr 4600. */
    PLAN_FEE_WITHHELD,
    /** A withheld plan fee credited back (voided or refunded invoice): Dr 4600 / Cr 2000:org (no order). */
    PLAN_FEE_RELEASED,
    /** A withheld plan fee recovered from (or a released one paid back with) an order's transfer. */
    PLAN_FEE_SETTLED,
    /** AfroTransact Retail supplier invoices released for payment (retail.payment.released, S226). */
    SUPPLIER_PAYABLE,
    /** A released supplier payment paid from the operating bank account. */
    SUPPLIER_PAYMENT,
    /** A correcting journal proposed and approved by Finance staff (ledger adjustment). */
    ADJUSTMENT
}
