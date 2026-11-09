package com.afrotransact.taxreturns.adapter.out.tax;

import java.util.List;

import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.InHouseTaxCalculator;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The visible fallback of a provider calculator: the rules engine answers, the quote's provider says
 * {@code RULES-FALLBACK}, the lines the provider could not take are listed on it, a counter is incremented and a warning
 * is logged. A fallback is never silent.
 */
final class ProviderFallback {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderFallback.class);

    private final InHouseTaxCalculator rules;
    private final TaxTelemetry telemetry;

    ProviderFallback(InHouseTaxCalculator rules, TaxTelemetry telemetry) {
        this.rules = rules;
        this.telemetry = telemetry;
    }

    TaxQuote quote(String provider, String why, String detail, TaxAddress shipTo, String currency,
                   List<TaxLineInput> lines, List<String> lineIds) {
        LOG.warn("{} cannot take {} line(s) ({}: {}); the rules engine quotes this order: {}", provider, lineIds.size(),
                why, detail, lineIds);
        telemetry.providerFallback(provider, why, lineIds.size());
        return rules.quote(shipTo, currency, lines).asFallback(lineIds);
    }
}
