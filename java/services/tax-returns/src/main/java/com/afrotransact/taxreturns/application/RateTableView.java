package com.afrotransact.taxreturns.application;

import java.util.List;

import com.afrotransact.taxreturns.domain.tax.LocalTaxRate;
import com.afrotransact.taxreturns.domain.tax.StateTaxRule;

/** The configured rate table. */
public record RateTableView(List<StateTaxRule> states, List<LocalTaxRate> locals) {

    public RateTableView {
        states = List.copyOf(states);
        locals = List.copyOf(locals);
    }
}
