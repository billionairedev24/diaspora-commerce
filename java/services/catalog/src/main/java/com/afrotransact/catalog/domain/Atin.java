package com.afrotransact.catalog.domain;

import java.util.Objects;

/** AfroTransact Identification Number — our ASIN equivalent, issued per product and per variant. */
public record Atin(String value) {

    public Atin {
        Objects.requireNonNull(value, "value");
        if (!value.matches("AT[0-9A-Z]{8}")) {
            throw new IllegalArgumentException("Invalid ATIN " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
