package com.afrotransact.catalog.domain.pictures;

import java.util.Locale;
import java.util.Optional;

/**
 * A named bundle of default picture-rule values. {@link #STRICT} is the documented standard and today's behaviour,
 * {@link #STARTUP} the relaxed launch profile, {@link #TESTING} makes every picture requirement optional. The same three
 * profiles exist in Go ({@code picturerules.Profile}); the golden vectors in {@code contracts/picture-rules} keep them equal.
 */
public enum PictureProfile {
    STRICT, STARTUP, TESTING;

    /** The wire name: lower case ({@code strict}). */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<PictureProfile> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.strip().toLowerCase(Locale.ROOT);
        for (PictureProfile p : values()) {
            if (p.wire().equals(t)) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /** The initial profile of an environment: local and dev testing, uat and prod startup. */
    public static PictureProfile forEnvironment(String environment) {
        String e = environment == null ? "" : environment.strip().toLowerCase(Locale.ROOT);
        return e.equals("uat") || e.equals("prod") ? STARTUP : TESTING;
    }
}
