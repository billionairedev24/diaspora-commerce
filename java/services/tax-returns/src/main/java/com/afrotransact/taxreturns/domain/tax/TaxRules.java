package com.afrotransact.taxreturns.domain.tax;

import java.util.List;

/** The default marketplace-facilitator rules engine: rate table × category × address → tax per line. */
public interface TaxRules {

    TaxQuote quote(TaxRateTable table, TaxAddress shipTo, String currency, List<TaxLineInput> lines);
}
