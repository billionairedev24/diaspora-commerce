package com.afrotransact.catalog.domain.brand;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Why a product has no brand: stored as {@code brand_reason} instead of a made-up brand such as "Generic". */
public enum UnbrandedReason {
    /** Sold without a brand or label. */
    UNBRANDED(Set.of("unbranded", "nobrand", "nobrandname", "nonbranded", "generic", "none", "na", "noname",
            "sansmarque", "sinmarca")),
    /** Made by hand or by an artisan, with no brand. */
    HANDMADE(Set.of("handmade", "handcrafted", "artisan", "artisanmade", "handmadenobrand"));

    private final Set<String> keywords;

    UnbrandedReason(Set<String> keywords) {
        this.keywords = keywords;
    }

    /** The reason a typed or uploaded value stands for ("N/A", "No brand", "Hand made"), if it does. */
    public static Optional<UnbrandedReason> ofText(String text) {
        String key = BrandNames.normalise(text);
        for (UnbrandedReason r : values()) {
            if (r.keywords.contains(key)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    /** The reason for a stored code such as "HANDMADE" (any case). */
    public static Optional<UnbrandedReason> ofCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
