package com.afrotransact.payments.domain;

/** State of an amount Stripe refused to take back from a seller's transfer. */
public enum TransferRecoveryStatus {
    /** Still owed to the platform: netted against the seller's next earnings, or resolved by Finance. */
    OPEN,
    /** Fully recovered, from later earnings or from money received outside Stripe. */
    RECOVERED,
    /** The platform absorbed what was left (an expense, 5300). */
    WRITTEN_OFF
}
