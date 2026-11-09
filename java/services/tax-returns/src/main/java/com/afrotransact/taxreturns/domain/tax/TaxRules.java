package com.afrotransact.taxreturns.domain.tax;

import java.util.List;

/** The default marketplace-facilitator rules engine: jurisdiction rates × category × address → tax per line. */
public interface TaxRules {

    /** Taxes the lines at the rates of {@code jurisdiction} (from a {@code JurisdictionResolver}). */
    TaxQuote quote(Jurisdiction jurisdiction, TaxAddress shipTo, String currency, List<TaxLineInput> lines);

    /** Taxes the lines at the rates the ZIP table gives for the address. */
    default TaxQuote quote(TaxRateTable table, TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        return quote(table.jurisdiction(shipTo), shipTo, currency, lines);
    }
}
