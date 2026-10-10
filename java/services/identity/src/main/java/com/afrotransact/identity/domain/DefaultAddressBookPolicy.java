package com.afrotransact.identity.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** US-first address rules; other countries need a line, city and postal code. */
public class DefaultAddressBookPolicy implements AddressBookPolicy {

    private static final Set<String> US_STATES = Set.of("AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL",
            "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE",
            "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD", "TN", "TX", "UT", "VT",
            "VA", "WA", "WV", "WI", "WY", "PR");

    @Override
    public Map<String, String> check(AddressDraft d) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (blank(d.fullName()) || d.fullName().trim().length() > 100) {
            errors.put("fullName", "Enter the recipient's full name");
        }
        if (blank(d.line1()) || d.line1().trim().length() < 3 || d.line1().length() > 120) {
            errors.put("line1", "Enter a street address");
        }
        if (d.line2() != null && d.line2().length() > 60) {
            errors.put("line2", "Apartment, suite or unit is too long");
        }
        if (blank(d.city()) || d.city().length() > 60) {
            errors.put("city", "Enter a city");
        }
        String country = blank(d.country()) ? "US" : d.country().trim().toUpperCase(Locale.ROOT);
        if (!country.matches("[A-Z]{2}")) {
            errors.put("country", "Use a two-letter country code");
        }
        if ("US".equals(country)) {
            String state = d.state() == null ? "" : d.state().trim().toUpperCase(Locale.ROOT);
            if (!US_STATES.contains(state)) {
                errors.put("state", "Choose a US state");
            }
            if (d.postalCode() == null || !d.postalCode().trim().matches("\\d{5}(-\\d{4})?")) {
                errors.put("postalCode", "ZIP code must be 5 digits or ZIP+4");
            }
        } else if (blank(d.postalCode())) {
            errors.put("postalCode", "Enter a postal code");
        }
        if (d.instructions() != null && d.instructions().length() > 300) {
            errors.put("instructions", "Delivery instructions are limited to 300 characters");
        }
        if (d.label() != null && d.label().length() > 30) {
            errors.put("label", "Label is limited to 30 characters");
        }
        return errors;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
