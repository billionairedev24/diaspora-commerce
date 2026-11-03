package com.afrotransact.taxreturns.domain.tax;

import java.time.Instant;

/** One change of the tax method, kept for audit. */
public record TaxMethodChange(TaxMethod from, TaxMethod to, String reason, String changedBy, Instant changedAt) {
}
