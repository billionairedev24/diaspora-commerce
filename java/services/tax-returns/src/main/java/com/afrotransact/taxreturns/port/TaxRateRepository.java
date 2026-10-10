package com.afrotransact.taxreturns.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.taxreturns.domain.tax.LocalTaxRate;
import com.afrotransact.taxreturns.domain.tax.StateTaxRule;
import com.afrotransact.taxreturns.domain.tax.TaxRateTable;

/** The configurable rate table (seeded by Flyway, edited by finance). */
public interface TaxRateRepository {

    TaxRateTable table();

    List<StateTaxRule> states();

    Optional<StateTaxRule> state(String country, String state);

    List<LocalTaxRate> locals();

    StateTaxRule upsertState(StateTaxRule rule);

    LocalTaxRate upsertLocal(LocalTaxRate rate);

    boolean deleteLocal(String state, String zipPrefix);
}
