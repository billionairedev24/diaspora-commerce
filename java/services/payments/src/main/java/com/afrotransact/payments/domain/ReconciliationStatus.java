package com.afrotransact.payments.domain;

/** Where a reconciliation difference stands in Finance's workflow. */
public enum ReconciliationStatus {
    /** Nobody has dealt with it yet. */
    OPEN,
    /** Finance looked and wrote down why the ledger and Stripe differ (timing, a known fee); nothing was posted. */
    EXPLAINED,
    /** A correcting journal for it was approved and posted; the next run confirms the difference is gone. */
    ADJUSTED,
    /** A later run of the same day replaced it (it was fixed, or it carries on as a new item). */
    SUPERSEDED
}
