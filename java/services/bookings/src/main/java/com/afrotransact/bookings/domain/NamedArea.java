package com.afrotransact.bookings.domain;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An extra area a provider serves beyond its radius, by name: {@code CITY} ("Taylor", state TX), {@code ZIP}
 * ("76574") or {@code ZONE} (a metro zone key such as "greater-austin").
 */
public record NamedArea(AreaKind kind, String value, String state) {

    private static final Pattern ZIP = Pattern.compile("^\\d{5}$");
    private static final Pattern STATE = Pattern.compile("^[A-Z]{2}$");

    public NamedArea {
        if (kind == null || value == null || value.isBlank()) {
            throw invalid("Name a city, a ZIP code or a zone");
        }
        value = value.trim();
        state = state == null || state.isBlank() ? null : state.trim().toUpperCase(Locale.ROOT);
        switch (kind) {
            case ZIP -> {
                if (!ZIP.matcher(value).matches()) {
                    throw invalid("A ZIP code has 5 digits");
                }
                state = null;
            }
            case CITY -> {
                if (state == null || !STATE.matcher(state).matches()) {
                    throw invalid("A city needs its 2-letter state");
                }
            }
            case ZONE -> {
                value = value.toLowerCase(Locale.ROOT);
                state = null;
            }
            default -> throw invalid("Unknown area kind");
        }
    }

    private static BookingsRuleViolation invalid(String message) {
        return new BookingsRuleViolation("bookings.invalid-service-area", "Service area is invalid",
                Map.of("extraAreas", message));
    }

    /** True when this named area contains the customer's place; zones are resolved through {@code zones}. */
    boolean covers(CustomerPlace c, Zones zones) {
        return switch (kind) {
            case ZIP -> value.equals(c.zip());
            case CITY -> value.equalsIgnoreCase(c.city()) && state.equals(c.state());
            case ZONE -> zones.zone(value).map(z -> z.contains(c.city(), c.state())).orElse(false);
        };
    }
}
