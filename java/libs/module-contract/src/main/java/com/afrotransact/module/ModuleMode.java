package com.afrotransact.module;

import java.util.Locale;

/** How a module reaches the other modules it depends on: {@code at.<module>.mode}. */
public enum ModuleMode {

    /** Call the other module's published API inside this JVM, as an explicit caller principal. */
    IN_PROCESS,
    /** Call the other module over HTTP with client-credentials tokens, as the standalone service does. */
    HTTP;

    /** Parses {@code in-process} or {@code http} (any case, {@code _} accepted); a blank value gives {@code fallback}. */
    public static ModuleMode parse(String value, ModuleMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "in-process", "inprocess" -> IN_PROCESS;
            case "http" -> HTTP;
            default -> throw new IllegalArgumentException("Unknown module mode '" + value + "': use in-process or http");
        };
    }
}
