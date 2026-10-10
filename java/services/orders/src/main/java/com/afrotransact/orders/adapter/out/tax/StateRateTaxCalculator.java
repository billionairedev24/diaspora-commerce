package com.afrotransact.orders.adapter.out.tax;

import java.util.Locale;
import java.util.Map;

import com.afrotransact.orders.config.OrdersProperties;
import com.afrotransact.orders.port.TaxCalculator;
import org.springframework.stereotype.Component;

/**
 * Interim marketplace-facilitator tax: one combined rate per US state from {@code at.orders.tax.rates} (basis points),
 * a default for states not listed. Replaced by the tax service (tax-returns) later.
 */
@Component
class StateRateTaxCalculator implements TaxCalculator {

    private final Map<String, Integer> rates;
    private final int defaultBps;

    StateRateTaxCalculator(OrdersProperties props) {
        this.rates = props.tax().rates();
        this.defaultBps = props.tax().defaultBps();
    }

    @Override
    public Rate rateFor(String country, String state, String postalCode) {
        String c = country == null || country.isBlank() ? "US" : country.trim().toUpperCase(Locale.ROOT);
        String s = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
        if (!"US".equals(c)) {
            return new Rate(0, c);
        }
        return new Rate(rates.getOrDefault(s, defaultBps), s.isEmpty() ? "US" : "US-" + s);
    }
}
