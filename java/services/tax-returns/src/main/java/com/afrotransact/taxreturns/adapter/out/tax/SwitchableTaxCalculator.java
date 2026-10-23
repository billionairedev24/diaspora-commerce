package com.afrotransact.taxreturns.adapter.out.tax;

import java.util.List;

import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.TaxCalculator;
import com.afrotransact.taxreturns.port.TaxMethodSource;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The {@link TaxCalculator} the application uses: it answers with our own rules or with Stripe Tax according to the
 * method staff set in Settings. Our rules are always the answer when Stripe Tax is not chosen or not set up.
 */
@Component
@Primary
class SwitchableTaxCalculator implements TaxCalculator {

    private final TaxMethodSource method;
    private final RulesTaxCalculator rules;
    private final StripeTaxCalculator stripe;

    SwitchableTaxCalculator(TaxMethodSource method, RulesTaxCalculator rules, StripeTaxCalculator stripe) {
        this.method = method;
        this.rules = rules;
        this.stripe = stripe;
    }

    @Override
    public TaxQuote quote(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        if (method.method() == TaxMethod.STRIPE_TAX && stripe.configured()) {
            return stripe.quote(shipTo, currency, lines);
        }
        return rules.quote(shipTo, currency, lines);
    }
}
