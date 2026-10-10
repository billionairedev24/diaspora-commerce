package com.afrotransact.orders.domain;

import java.util.Locale;

/** Stage of a pre-order window as delivery-runs derives it from its dates (S270). */
public enum PreorderStage {
    UPCOMING,
    OPEN,
    CLOSED,
    DELIVERED,
    /** Anything else (draft, paused, unknown): never orderable. */
    UNAVAILABLE;

    public static PreorderStage parse(String value) {
        if (value == null || value.isBlank()) {
            return UNAVAILABLE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNAVAILABLE;
        }
    }
}
