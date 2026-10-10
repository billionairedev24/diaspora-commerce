package com.afrotransact.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A saved delivery address in a customer's address book. Addresses are entered through address autocomplete and
 * validated by the geo service before they reach here; {@code verified} records that outcome.
 *
 * @param instructions delivery preferences and instructions for drivers (gate code, leave with concierge…)
 */
public record PostalAddress(UUID id, UUID userId, String label, String fullName, String phone, String line1,
                            String line2, String city, String state, String postalCode, String country,
                            Double latitude, Double longitude, String instructions, boolean verified,
                            boolean isDefault, Instant createdAt, Instant updatedAt) {

    public PostalAddress {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(line1, "line1");
        Objects.requireNonNull(country, "country");
    }

    public PostalAddress asDefault(boolean value) {
        return new PostalAddress(id, userId, label, fullName, phone, line1, line2, city, state, postalCode, country,
                latitude, longitude, instructions, verified, value, createdAt, updatedAt);
    }
}
