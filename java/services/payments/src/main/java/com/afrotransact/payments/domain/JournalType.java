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
    PROVIDER_FEE_COLLECTED
}
