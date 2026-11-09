package com.afrotransact.taxreturns.adapter.out.tax;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.domain.tax.TaxRules;
import com.afrotransact.taxreturns.port.InHouseTaxCalculator;
import com.afrotransact.taxreturns.port.JurisdictionResolver;
import org.springframework.stereotype.Component;

/**
 * The in-house {@code TaxCalculator}: the domain rules engine over the rates a {@link JurisdictionResolver} finds. No
 * external calls. It taxes the lines it is given; deciding which lines are taxable at all is the stored decision's job
 * (exempt lines never reach a calculator).
 */
@Component
class RulesTaxCalculator implements InHouseTaxCalculator {

    private final JurisdictionResolver jurisdictions;
    private final TaxRules rules;
    private final Clock clock;

    RulesTaxCalculator(JurisdictionResolver jurisdictions, TaxRules rules, Clock clock) {
        this.jurisdictions = jurisdictions;
        this.rules = rules;
        this.clock = clock;
    }

    @Override
    public TaxQuote quote(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        return rules.quote(jurisdictions.resolve(shipTo, LocalDate.now(clock)), shipTo, currency, lines);
    }
}
