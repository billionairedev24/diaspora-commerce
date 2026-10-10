package com.afrotransact.catalog.domain;

/** Who flagged a live listing. */
public enum FlagSource {
    /** A signed-in shopper used "Report this listing". */
    CUSTOMER_REPORT,
    /** Staff flagged it for review. */
    STAFF,
    /** A service (AI triage, rules) flagged it automatically. */
    AUTOMATED
}
