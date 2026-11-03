package com.afrotransact.taxreturns.application;

import java.util.List;

/** Outcome of a tax rate table CSV import. */
public record TaxRatesImportResult(int statesUpserted, int localsUpserted, List<String> warnings) {
}
