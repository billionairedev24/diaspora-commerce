package com.afrotransact.bookings.domain;

import java.util.Objects;

/**
 * An optional extra on a service (e.g. "Hair included", "Extra length").
 *
 * @param extraMinutes time the add-on adds to the appointment
 */
public record AddOn(String id, String name, long priceMinor, int extraMinutes) {

    public AddOn {
        Objects.requireNonNull(id, "id");
        if (name == null || name.isBlank()) {
            throw new BookingsRuleViolation("bookings.invalid-service", "Add-on name is required");
        }
        if (priceMinor < 0) {
            throw new BookingsRuleViolation("bookings.invalid-service", "Add-on price must not be negative");
        }
        if (extraMinutes < 0 || extraMinutes > 480) {
            throw new BookingsRuleViolation("bookings.invalid-service", "Add-on time must be 0–480 minutes");
        }
        name = name.trim();
    }
}
