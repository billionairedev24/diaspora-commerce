package com.afrotransact.payments.domain;

public enum AdjustmentStatus {
    /** Waiting for a different person to approve or reject it. */
    PROPOSED,
    /** Approved: its journal is in the ledger. */
    POSTED,
    REJECTED
}
