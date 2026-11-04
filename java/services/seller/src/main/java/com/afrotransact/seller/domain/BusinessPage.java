package com.afrotransact.seller.domain;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;

/**
 * The plain public page of a business without a website: its address (slug) and when it was made. What the page shows
 * comes from the application's business step; it is never edited on its own.
 */
public record BusinessPage(String orgId, String slug, Instant createdAt) {

    public static final int MAX_SLUG = 60;

    /** "Ade's Kitchen & Catering" becomes "ades-kitchen-catering"; names without letters or digits become "business". */
    public static String slugOf(String name) {
        String ascii = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace("'", "").replace("’", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_SLUG) {
            slug = slug.substring(0, MAX_SLUG).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "business" : slug;
    }
}
