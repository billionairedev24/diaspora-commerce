package com.afrotransact.fulfilment.adapter.in.web;

import java.util.Locale;

/** Lenient enum parsing for driver payloads: unknown values become null and the domain rules report them. */
final class Enums {

    private Enums() {
    }

    static <E extends Enum<E>> E lenient(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Strict parsing for console requests (400 on unknown values). */
    static <E extends Enum<E>> E strict(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        E e = lenient(type, value);
        if (e == null) {
            throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " " + value);
        }
        return e;
    }
}
