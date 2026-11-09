package com.afrotransact.taxreturns.domain.tax;

import java.util.List;

/**
 * Tax for a set of lines shipped to one address.
 *
 * @param provider      who worked it out: {@code rules}, {@code STRIPE_TAX}, {@code AVALARA}, {@code EXEMPT}, or
 *                      {@code RULES-FALLBACK} when a chosen provider could not take the quote and the rules engine
 *                      answered instead (never hidden: see {@code fallbackLines})
 * @param fallbackLines ids of the lines the provider could not take (all lines when it was unreachable or not set up)
 */
public record TaxQuote(String currency, String jurisdiction, CollectedBy collectedBy, List<TaxLineResult> lines,
                       String provider, List<String> fallbackLines) {

    public static final String RULES_FALLBACK = "RULES-FALLBACK";

    public TaxQuote {
        lines = List.copyOf(lines);
        fallbackLines = fallbackLines == null ? List.of() : List.copyOf(fallbackLines);
    }

    public TaxQuote(String currency, String jurisdiction, CollectedBy collectedBy, List<TaxLineResult> lines,
                    String provider) {
        this(currency, jurisdiction, collectedBy, lines, provider, List.of());
    }

    public long totalTaxMinor() {
        return lines.stream().mapToLong(TaxLineResult::taxMinor).sum();
    }

    /** The same quote answered by the fallback: provider RULES-FALLBACK and the lines the provider could not take. */
    public TaxQuote asFallback(List<String> lineIds) {
        return new TaxQuote(currency, jurisdiction, collectedBy, lines, RULES_FALLBACK, lineIds);
    }
}
