package com.afrotransact.content.domain.privacy;

import java.util.Locale;
import java.util.Optional;

/** What a visitor asks for on Your Privacy Choices. */
public enum PrivacyRequestType {
    /** Get a copy of my data. */
    ACCESS("access"),
    /** Delete my data. */
    DELETE("delete"),
    /** Correct my data. */
    CORRECT("correct"),
    /** Opt out of sale, sharing and targeted ads. */
    OPT_OUT("optout");

    private final String key;

    PrivacyRequestType(String key) {
        this.key = key;
    }

    /** Form value used by the storefront and app. */
    public String key() {
        return key;
    }

    /** Accepts the form key ({@code optout}) or the enum name ({@code OPT_OUT}), case-insensitively. */
    public static Optional<PrivacyRequestType> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        for (PrivacyRequestType t : values()) {
            if (t.key.equals(v) || t.name().toLowerCase(Locale.ROOT).equals(v)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
