package com.afrotransact.taxreturns.domain.tax;

import java.util.List;

/** Tax for a set of lines shipped to one address. */
public record TaxQuote(String currency, String jurisdiction, CollectedBy collectedBy, List<TaxLineResult> lines,
                       String provider) {

    public TaxQuote {
        lines = List.copyOf(lines);
    }

    public long totalTaxMinor() {
        return lines.stream().mapToLong(TaxLineResult::taxMinor).sum();
    }
}
