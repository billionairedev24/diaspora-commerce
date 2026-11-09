package com.afrotransact.taxreturns.adapter.out.tax;

import java.util.List;

import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxMethod;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.InHouseTaxCalculator;
import com.afrotransact.taxreturns.port.ProviderTaxCalculator;
import com.afrotransact.taxreturns.port.TaxCalculator;
import com.afrotransact.taxreturns.port.TaxMethodSource;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The {@link TaxCalculator} the application uses: it answers with our own rules, Stripe Tax or Avalara according to the
 * method staff set in Settings. A provider that cannot take the quote (not set up, unreachable, a line without a code)
 * answers through the visible rules fallback (provider RULES-FALLBACK), never silently.
 */
@Component
@Primary
class SwitchableTaxCalculator implements TaxCalculator {

    private final TaxMethodSource method;
    private final InHouseTaxCalculator rules;
    private final List<ProviderTaxCalculator> providers;

    SwitchableTaxCalculator(TaxMethodSource method, InHouseTaxCalculator rules, List<ProviderTaxCalculator> providers) {
        this.method = method;
        this.rules = rules;
        this.providers = providers;
    }

    @Override
    public TaxQuote quote(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        TaxMethod chosen = method.method();
        TaxProviderId id = chosen.provider();
        if (id == null) {
            return rules.quote(shipTo, currency, lines);
        }
        return providers.stream().filter(p -> p.provider() == id).findFirst()
                .map(p -> p.quote(shipTo, currency, lines)).orElseGet(() -> rules.quote(shipTo, currency, lines));
    }
}
