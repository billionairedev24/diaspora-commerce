package com.afrotransact.catalog.domain;

import java.util.Objects;

/** An uploaded image (UploadThing / object storage key) with its pixel size. */
public record ProductImage(String url, int width, int height, long bytes, String contentType) {

    public static final int MIN_MAIN_EDGE = 1000;
    public static final long MAX_BYTES = 8L * 1024 * 1024;

    public ProductImage {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(contentType, "contentType");
    }

    public boolean largeEnoughForMain() {
        return width >= MIN_MAIN_EDGE && height >= MIN_MAIN_EDGE;
    }

    public boolean allowedType() {
        return "image/jpeg".equals(contentType) || "image/png".equals(contentType) || "image/webp".equals(contentType);
    }
}
