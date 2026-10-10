package com.afrotransact.retail.domain;

import java.math.BigDecimal;
import java.util.List;

/** Outcome of matching one supplier invoice against its PO and goods receipts. */
public record MatchResult(MatchOutcome outcome, List<MatchLine> lines, long expectedTotalMinor,
                          long invoicedTotalMinor, BigDecimal totalVariancePercent, List<String> reasons) {

    public MatchResult {
        lines = List.copyOf(lines);
        reasons = List.copyOf(reasons);
    }
}
