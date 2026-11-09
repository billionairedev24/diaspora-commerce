package com.afrotransact.taxreturns.adapter.out.tax;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.InHouseTaxCalculator;
import com.afrotransact.taxreturns.port.ProviderTaxCalculator;
import com.afrotransact.taxreturns.port.TaxProviderException;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.springframework.stereotype.Component;

/**
 * SKELETON {@link ProviderTaxCalculator} for Avalara AvaTax. What exists: the config keys
 * ({@code at.taxreturns.tax.avalara.*}), the request mapping of a quote to an AvaTax transaction
 * ({@link #request}), the {@code configured()} check that settings validation uses, and a clear "not configured" /
 * "not implemented" answer. What does not: the HTTP call to {@code POST /api/v2/transactions/create} and the response
 * parsing. Until they are written, asking Avalara fails strictly (shadow mode records PROVIDER_ERROR) and a charging quote
 * falls back visibly to the rules engine (provider RULES-FALLBACK). The Avalara tax codes come from the code map
 * (provider AVALARA), which is empty until staff fill it.
 */
@Component
class AvalaraTaxCalculator implements ProviderTaxCalculator {

    private final TaxReturnsProperties.AvalaraTax config;
    private final ProviderFallback fallback;
    private final Clock clock;

    AvalaraTaxCalculator(TaxReturnsProperties props, InHouseTaxCalculator rules, TaxTelemetry telemetry, Clock clock) {
        this.config = props.tax().avalara();
        this.fallback = new ProviderFallback(rules, telemetry);
        this.clock = clock;
    }

    @Override
    public TaxProviderId provider() {
        return TaxProviderId.AVALARA;
    }

    @Override
    public boolean configured() {
        return config.configured();
    }

    @Override
    public TaxQuote quote(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        List<String> all = lines.stream().map(TaxLineInput::lineId).toList();
        String why = configured() ? "not-implemented" : "not-configured";
        return fallback.quote("AVALARA", why, unavailable().getMessage(), shipTo, currency, lines, all);
    }

    @Override
    public TaxQuote quoteStrict(TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        throw unavailable();
    }

    private TaxProviderException unavailable() {
        if (!configured()) {
            return new TaxProviderException("Avalara is not configured: set AVALARA_ACCOUNT_ID, AVALARA_LICENSE_KEY and "
                    + "AVALARA_COMPANY_CODE");
        }
        return new TaxProviderException("The Avalara AvaTax call is not implemented yet (skeleton adapter)");
    }

    /**
     * Maps a quote to an AvaTax {@code CreateTransactionModel} (a SalesOrder that is not committed). Lines use the
     * provider code of the code map for the line's outcome; a line without one is left out of the request and listed in
     * {@code missingCodes} by the caller beforehand.
     */
    Map<String, Object> request(TaxAddress shipTo, String currency, List<TaxLineInput> lines, String reference) {
        Map<String, Object> ship = new LinkedHashMap<>();
        if (shipTo.city() != null && !shipTo.city().isBlank()) {
            ship.put("city", shipTo.city());
        }
        ship.put("region", shipTo.state());
        ship.put("country", shipTo.country());
        ship.put("postalCode", shipTo.postalCode());
        List<Map<String, Object>> items = new ArrayList<>();
        int n = 1;
        for (TaxLineInput l : lines) {
            String code = l.codeFor(TaxProviderId.AVALARA);
            if (code == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("number", Integer.toString(n++));
            item.put("quantity", l.quantity());
            item.put("amount", minorToMajor(l.taxableItemsMinor()));
            item.put("taxCode", code);
            item.put("itemCode", l.atin() == null ? l.lineId() : l.atin());
            item.put("ref1", l.lineId());
            item.put("taxIncluded", false);
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "SalesOrder");
        body.put("companyCode", config.companyCode());
        body.put("date", LocalDate.now(clock).toString());
        body.put("customerCode", reference == null ? "guest" : reference);
        body.put("currencyCode", currency);
        body.put("commit", false);
        body.put("addresses", Map.of("shipTo", ship));
        body.put("lines", items);
        return body;
    }

    /** Two-decimal currencies only (USD at launch); other currencies need the currency's exponent. */
    private static String minorToMajor(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }
}
