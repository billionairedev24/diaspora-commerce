package com.afrotransact.identity.domain;

import java.util.Locale;
import java.util.Map;

/**
 * Market codes as content-service defines them (S215): ISO 3166-1 alpha-2, upper case ({@code US}, {@code CA},
 * {@code GB}). Common aliases are mapped ({@code UK} → {@code GB}); anything else that is not two letters is unknown.
 */
public final class MarketCodes {

    private static final Map<String, String> ALIASES = Map.of("UK", "GB", "USA", "US", "CAN", "CA", "GBR", "GB",
            "NGA", "NG", "GHA", "GH");

    private MarketCodes() {
    }

    /** The content market code for {@code raw}, or null when it is blank or not a country code. */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String code = raw.trim().toUpperCase(Locale.ROOT);
        code = ALIASES.getOrDefault(code, code);
        return code.matches("[A-Z]{2}") ? code : null;
    }
}
