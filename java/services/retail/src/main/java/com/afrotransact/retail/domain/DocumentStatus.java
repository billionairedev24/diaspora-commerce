package com.afrotransact.retail.domain;

/** Status of a customs / shipping document. MISSING blocks departure; HOLD blocks customs clearance. */
public enum DocumentStatus {
    MISSING,
    PENDING,
    OK,
    HOLD
}
