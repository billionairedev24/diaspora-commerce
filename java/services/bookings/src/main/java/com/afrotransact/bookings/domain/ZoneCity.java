package com.afrotransact.bookings.domain;

import java.util.Locale;

/** A city of a metro zone ("Round Rock", "TX"). */
public record ZoneCity(String city, String state) {

    public ZoneCity {
        if (city == null || city.isBlank() || state == null || state.trim().length() != 2) {
            throw new BookingsRuleViolation("bookings.invalid-zone", "Zone city is invalid");
        }
        city = city.trim();
        state = state.trim().toUpperCase(Locale.ROOT);
    }

    boolean is(String otherCity, String otherState) {
        return city.equalsIgnoreCase(otherCity) && state.equalsIgnoreCase(otherState);
    }
}
