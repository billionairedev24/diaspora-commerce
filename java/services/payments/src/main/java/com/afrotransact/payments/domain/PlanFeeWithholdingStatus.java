package com.afrotransact.payments.domain;

/** State of a seller plan fee withheld from earnings (S298). */
public enum PlanFeeWithholdingStatus {
    /**
     * Debited from the seller's payable (Dr 2000:org / Cr 4600) and recovered from the organisation's next transfers;
     * possibly released in part.
     */
    WITHHELD,
    /** Nothing could be withheld (no delivered, undisputed earnings left): the card pays the whole invoice. */
    DECLINED,
    /** Released in full (invoice voided, deleted or refunded): credited back to the seller's next transfer. */
    RELEASED
}
