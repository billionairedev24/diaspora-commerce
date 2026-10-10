package com.afrotransact.taxreturns.application;

import java.time.YearMonth;
import java.util.List;

import com.afrotransact.taxreturns.domain.tax.LiabilityRow;

/** Monthly tax liability per jurisdiction (whole marketplace, or one seller's statement when sellerOrgId is set). */
public record LiabilityReport(YearMonth month, String sellerOrgId, List<LiabilityRow> rows) {

    public LiabilityReport {
        rows = List.copyOf(rows);
    }
}
