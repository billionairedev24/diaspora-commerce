package com.afrotransact.taxreturns.application;

import com.afrotransact.taxreturns.domain.tax.LocalTaxRate;
import com.afrotransact.taxreturns.domain.tax.StateTaxRule;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;

/** Tax quotes and the rate table (S116). */
public interface TaxService {

    /** Tax per line for a cart or order (tax:quote). */
    TaxQuote quote(TaxQuoteCommand command);

    RateTableView rates();

    StateTaxRule upsertState(StateTaxRule rule);

    LocalTaxRate upsertLocal(LocalTaxRate rate);

    void deleteLocal(String state, String zipPrefix);

    /** Upsert every STATE and LOCAL row from a CSV upload (tax:configure). */
    TaxRatesImportResult importCsv(String csv);
}
