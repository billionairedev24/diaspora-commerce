package com.afrotransact.seller.domain;

import java.util.Locale;

/** Billing frequency: monthly, quarterly (5 % off) or annual (2 months free). */
public enum Cadence {
    MONTHLY(1, "every month"),
    QUARTERLY(3, "every 3 months"),
    ANNUAL(12, "every year");

    private final int months;
    private final String every;

    Cadence(int months, String every) {
        this.months = months;
        this.every = every;
    }

    public int months() {
        return months;
    }

    public String every() {
        return every;
    }

    public static Cadence parse(String raw) {
        String k = raw.trim().toUpperCase(Locale.ROOT);
        return switch (k) {
            case "MONTH", "MONTHLY" -> MONTHLY;
            case "QUARTER", "QUARTERLY" -> QUARTERLY;
            case "YEAR", "ANNUAL", "ANNUALLY", "YEARLY" -> ANNUAL;
            default -> throw new SellerRuleViolation("seller.invalid-cadence", "Unknown billing cadence " + raw);
        };
    }
}
