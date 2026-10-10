package com.afrotransact.retail.domain;

/** Three-way match result: pay, route the variance to the buyer, or hold payment (awaiting goods or claims). */
public enum MatchOutcome {
    MATCH,
    EXCEPTION,
    HOLD
}
