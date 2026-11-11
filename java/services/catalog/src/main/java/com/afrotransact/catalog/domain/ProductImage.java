package com.afrotransact.catalog.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * An uploaded image (UploadThing / object storage key) with its pixel size and optional alternative text.
 *
 * @param role null for a product picture; {@link #LABEL} for the label photo (ingredients and nutrition panel,
 *             Supplement Facts, Drug Facts) that some kinds of product need as a typed image slot
 */
public record ProductImage(String url, int width, int height, long bytes, String contentType, String alt, String role) {

    public static final long MAX_BYTES = 8L * 1024 * 1024;
    /** The role of the label photo. */
    public static final String LABEL = "LABEL";

    public ProductImage {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(contentType, "contentType");
        role = role == null || role.isBlank() ? null : role.strip().toUpperCase(Locale.ROOT);
    }

    public ProductImage(String url, int width, int height, long bytes, String contentType, String alt) {
        this(url, width, height, bytes, contentType, alt, null);
    }

    public ProductImage(String url, int width, int height, long bytes, String contentType) {
        this(url, width, height, bytes, contentType, null, null);
    }

    public boolean allowedType() {
        return "image/jpeg".equals(contentType) || "image/png".equals(contentType) || "image/webp".equals(contentType);
    }

    /** True for the label photo. */
    public boolean isLabel() {
        return LABEL.equals(role);
    }

    /** The same image as the label photo. */
    public ProductImage asLabel() {
        return new ProductImage(url, width, height, bytes, contentType, alt, LABEL);
    }
}
