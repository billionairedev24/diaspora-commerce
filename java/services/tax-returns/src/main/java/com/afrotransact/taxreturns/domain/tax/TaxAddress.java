package com.afrotransact.taxreturns.domain.tax;

import java.util.Locale;

/**
 * An address that decides a tax jurisdiction: the ship-to, or a ship-from (origin). Only what is known is held; a street
 * is never made up.
 *
 * @param line1     street line, or empty when only the ZIP or city was known
 * @param precision how exactly the place was known: {@code ADDRESS} (a street was given), {@code CITY} (the ZIP was
 *                  resolved from a city name) or {@code ZIP}; it is {@code ADDRESS} whenever a street is present
 */
public record TaxAddress(String country, String state, String postalCode, String city, String line1, String precision) {

    public static final String ADDRESS = "ADDRESS";
    public static final String CITY = "CITY";
    public static final String ZIP = "ZIP";

    public TaxAddress {
        country = country == null || country.isBlank() ? "US" : country.trim().toUpperCase(Locale.ROOT);
        state = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
        postalCode = postalCode == null ? "" : postalCode.trim();
        line1 = line1 == null ? "" : line1.trim();
        precision = !line1.isEmpty() ? ADDRESS : CITY.equals(precision) ? CITY : ZIP;
    }

    public TaxAddress(String country, String state, String postalCode, String city) {
        this(country, state, postalCode, city, null, null);
    }

    /** The same place with a street line (the precision becomes ADDRESS). */
    public TaxAddress withLine1(String street) {
        return new TaxAddress(country, state, postalCode, city, street, precision);
    }

    /** The same place, known only down to the city (the ZIP was resolved from a city name). */
    public TaxAddress withCityPrecision() {
        return new TaxAddress(country, state, postalCode, city, line1, CITY);
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
