package com.afrotransact.taxreturns.adapter.out.tax;

import java.util.List;

import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.domain.tax.TaxRules;
import com.afrotransact.taxreturns.port.TaxCalculator;
import com.afrotransact.taxreturns.port.TaxRateRepository;
import org.springframework.stereotype.Component;

/**
 * Default {@link TaxCalculator}: the domain rules engine over the rate table in PostgreSQL. No external calls. An
 * external provider (Stripe Tax, TaxJar, Avalara) would be another adapter of the same port selected by
 * {@code at.taxreturns.tax.provider}.
 */
@Component
class RulesTaxCalculator implements TaxCalculator {

    private final TaxRateRepository rates;
    private final TaxRules rules;

    RulesTaxCalculator(TaxRateRepository rates, TaxRules rules) {
        this.rates = rates;
        this.rules = rules;
    }

    @Override
    public TaxQuote quote(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        return rules.quote(rates.table(), shipTo, currency, lines);
    }
}
