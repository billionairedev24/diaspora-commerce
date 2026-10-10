package com.afrotransact.payments.domain;

/** State of a carrier label charged to a seller. */
public enum LabelChargeStatus {
    /** Debited from the seller's payable (Dr 2000:org / Cr 2400, Cr 4500). */
    CHARGED,
    /** The label was voided after the charge: credited back once (Dr 2400, Dr 4500 / Cr 2000:org). */
    CREDITED,
    /** The void arrived before any charge: nothing was posted, and a late purchase event charges nothing. */
    VOIDED
}
