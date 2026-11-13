package com.afrotransact.catalog.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * An uploaded image (UploadThing / object storage key) with its pixel size and optional alternative text.
 *
 * @param role null for a product picture; {@link #LABEL} for the label photo (ingredients and nutrition panel,
 *             Supplement Facts, Drug Facts) that some kinds of product need as a typed image slot
 * @param assetId media's asset id, set while the picture is held for review: a held picture has no public URL yet
 *             ({@code url} is empty), so the listing refers to it by id until media clears it (S390). Null once the
 *             picture is published or when it was never held.
 */
public record ProductImage(String url, int width, int height, long bytes, String contentType, String alt, String role,
                           String assetId) {

    public static final long MAX_BYTES = 8L * 1024 * 1024;
    /** The role of the label photo. */
    public static final String LABEL = "LABEL";

    public ProductImage {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(contentType, "contentType");
        role = role == null || role.isBlank() ? null : role.strip().toUpperCase(Locale.ROOT);
        assetId = assetId == null || assetId.isBlank() ? null : assetId.strip();
    }

    public ProductImage(String url, int width, int height, long bytes, String contentType, String alt, String role) {
        this(url, width, height, bytes, contentType, alt, role, null);
    }

    /** A picture media holds for review: only its asset id is known, there is no public URL to show or sell with. */
    public static ProductImage held(String assetId, String alt, String role) {
        return new ProductImage("", 0, 0, 0, "", alt, role, assetId);
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

    /** True while the picture is held for review: it has an asset id and no public URL. */
    public boolean isHeld() {
        return assetId != null && url.isBlank();
    }

    /** The same picture once media published it: its public URL and measurements, no asset id any more. */
    public ProductImage published(String publicUrl, int w, int h, long size, String type) {
        return new ProductImage(publicUrl, w, h, size, type == null || type.isBlank() ? contentType : type, alt, role, null);
    }

    /** True for the label photo. */
    public boolean isLabel() {
        return LABEL.equals(role);
    }

    /** The same image as the label photo. */
    public ProductImage asLabel() {
        return new ProductImage(url, width, height, bytes, contentType, alt, LABEL, assetId);
    }
}
