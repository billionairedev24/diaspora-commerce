package com.afrotransact.retail.domain;

/** Dock quality check; required before stock is sellable, FAIL quarantines the whole batch. */
public enum QaResult {
    PENDING,
    PASS,
    FAIL
}
