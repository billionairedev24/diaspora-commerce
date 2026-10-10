package com.afrotransact.retail.adapter.out.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.retail.port.FxRateProvider;

/**
 * Rates from configuration ({@code at.retail.fx-rates}): treasury updates them daily. Replace with a market-data
 * adapter (same port) when one is contracted.
 */
public class ConfiguredFxRates implements FxRateProvider {

    private final Map<String, BigDecimal> rates;

    public ConfiguredFxRates(Map<String, BigDecimal> rates) {
        this.rates = Map.copyOf(rates);
    }

    @Override
    public Optional<BigDecimal> unitsPerBase(String currency, LocalDate date) {
        return Optional.ofNullable(rates.get(currency.toUpperCase(Locale.ROOT)));
    }
}
