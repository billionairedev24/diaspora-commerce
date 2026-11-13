package com.afrotransact.catalog.domain.matching;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Whether an image address is a real photograph: the same rule as {@code searchindex.PhotoOK} in the Go services (an
 * http(s) or site-relative address, not svg, ico, inline data or a known placeholder). A page without one is never shown
 * as a match: a seller cannot tell it is theirs.
 */
public final class RealPhoto {

    private static final List<String> PLACEHOLDER_MARKS = List.of("placeholder", "no-image", "noimage", "no_image",
            "default-image", "coming-soon", "image-not-available", "/icons/", "/icon-");

    private RealPhoto() {
    }

    public static boolean is(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return false;
        }
        String low = imageUrl.strip().toLowerCase(Locale.ROOT);
        if (!low.startsWith("https://") && !low.startsWith("http://") && !low.startsWith("/")) {
            return false;
        }
        String path;
        try {
            path = URI.create(low).getPath();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (path == null) {
            return false;
        }
        if (path.endsWith(".svg") || path.endsWith(".ico")) {
            return false;
        }
        return PLACEHOLDER_MARKS.stream().noneMatch(path::contains);
    }
}
