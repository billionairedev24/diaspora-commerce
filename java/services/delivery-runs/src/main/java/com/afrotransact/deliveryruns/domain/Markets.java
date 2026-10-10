package com.afrotransact.deliveryruns.domain;

import java.util.Map;

/** Currency of each pre-order country (the design's market list). */
public final class Markets {

    private static final Map<String, String> CURRENCY = Map.of("US", "USD", "CA", "CAD", "GB", "GBP", "NG", "NGN",
            "GH", "GHS", "KE", "KES", "ZA", "ZAR", "JM", "JMD", "IE", "EUR");

    private Markets() {
    }

    /** The country's currency, USD when unknown. */
    public static String currencyOf(String country) {
        return country == null ? "USD" : CURRENCY.getOrDefault(country, "USD");
    }
}
