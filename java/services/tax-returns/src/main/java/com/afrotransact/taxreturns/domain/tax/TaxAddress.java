package com.afrotransact.taxreturns.domain.tax;

import java.util.Locale;

/** Ship-to address fields that decide the tax jurisdiction. */
public record TaxAddress(String country, String state, String postalCode, String city) {

    public TaxAddress {
        country = country == null || country.isBlank() ? "US" : country.trim().toUpperCase(Locale.ROOT);
        state = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
        postalCode = postalCode == null ? "" : postalCode.trim();
    }

    /** The 5-digit ZIP (ZIP+4 is cut), or empty. */
    public String zip5() {
        String digits = postalCode.length() >= 5 ? postalCode.substring(0, 5) : postalCode;
        return digits.matches("\\d{5}") ? digits : "";
    }

    /** State jurisdiction code ({@code US-TX}), or the country for addresses outside the US. */
    public String stateJurisdiction() {
        if (!"US".equals(country)) {
            return country;
        }
        return state.isEmpty() ? "US" : "US-" + state;
    }
}
