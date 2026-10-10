package com.afrotransact.retail.domain;

/** Claim raised by a discrepancy; open claims hold supplier payment. */
public enum ClaimStatus {
    OPEN,
    RESOLVED,
    NOT_REQUIRED
}
