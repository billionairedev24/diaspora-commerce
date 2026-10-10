package com.afrotransact.seller.domain;

/** Review reason code (seeded), e.g. ALL_CHECKS_PASSED, PERMIT_MISSING, TIN_MISMATCH. */
public record ReasonCode(String code, String label, String appliesTo) {
}
