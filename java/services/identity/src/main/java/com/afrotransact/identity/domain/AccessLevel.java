package com.afrotransact.identity.domain;

import java.util.Locale;

/** Module access level in the access matrix; the console cycles none → view → edit → approve → full. */
public enum AccessLevel {
    NONE,
    VIEW,
    EDIT,
    APPROVE,
    FULL;

    public AccessLevel next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AccessLevel parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
