package com.afrotransact.content.domain.placement;

import java.util.Locale;

import com.afrotransact.content.domain.ContentRuleViolation;

/** Placement slots. Each has a payload JSON Schema and a size spec in the slot catalogue. */
public enum SlotKey {
    ANNOUNCEMENT_BAR,
    HERO_CAROUSEL,
    HOME_TILES,
    HOME_PRODUCT_ROW,
    DISPLAY_BANNER,
    CATEGORY_BANNER,
    CHECKOUT_CROSS_SELL,
    /** Paid: full-width sponsored brand banner between rails. */
    HOME_BRAND_BANNER,
    /** Paid: products sellers promote, from the sponsored-products auction. */
    HOME_SPONSORED_PRODUCTS,
    /** Paid: two or three image ads side by side. */
    HOME_AD_ROW,
    /** Paid: editorial card from a partner. */
    HOME_ADVERTORIAL,
    /** Rule-based home module (deals, spotlights, bands). */
    HOME_BLOCK;

    /** Wire key, for example {@code hero_carousel}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SlotKey fromKey(String key) {
        if (key != null) {
            for (SlotKey s : values()) {
                if (s.key().equalsIgnoreCase(key.trim()) || s.name().equalsIgnoreCase(key.trim())) {
                    return s;
                }
            }
        }
        throw new ContentRuleViolation("content.unknown-slot", "Unknown slot " + key);
    }
}
