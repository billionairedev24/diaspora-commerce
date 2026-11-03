package com.afrotransact.deliveryruns.domain;

import java.util.regex.Pattern;

/**
 * A product staff feature in a location advertorial (S263): up to {@link #MAX_PER_ADVERTORIAL} per advertorial. The
 * title and picture are what staff saw when they chose it; the price is not kept (the product page shows today's), so
 * the card never quotes a stale one.
 *
 * @param atin     the product's variant ATIN (the product page is {@code /p/<atin>})
 * @param title    the product's name
 * @param imageUrl its picture (https), or null
 */
public record FeaturedProduct(String atin, String title, String imageUrl) {

    public static final int MAX_PER_ADVERTORIAL = 4;
    private static final Pattern ATIN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{3,39}$");
    private static final int MAX_TITLE = 140;
    private static final int MAX_IMAGE = 500;

    public FeaturedProduct {
        atin = atin == null ? "" : atin.trim();
        title = title == null ? "" : title.trim();
        imageUrl = imageUrl == null || imageUrl.isBlank() ? null : imageUrl.trim();
        if (!ATIN.matcher(atin).matches()) {
            throw new RuleViolation("delivery-runs.invalid-advertorial", "A featured product needs its product code");
        }
        if (title.isEmpty() || title.length() > MAX_TITLE) {
            throw new RuleViolation("delivery-runs.invalid-advertorial",
                    "A featured product's name is 1 to " + MAX_TITLE + " characters");
        }
        if (imageUrl != null && (imageUrl.length() > MAX_IMAGE || !imageUrl.startsWith("https://"))) {
            throw new RuleViolation("delivery-runs.invalid-advertorial",
                    "A featured product's picture is an https address of at most " + MAX_IMAGE + " characters");
        }
    }
}
