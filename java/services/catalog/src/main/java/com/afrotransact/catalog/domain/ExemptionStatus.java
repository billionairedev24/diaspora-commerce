package com.afrotransact.catalog.domain;

/** The state of a request to sell an item without a barcode. */
public enum ExemptionStatus {
    PENDING,
    APPROVED,
    REJECTED
}
