package com.afrotransact.catalog.domain.matching;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Whether an image address is a real photograph: the same rule as {@code picturerules.IsRealPhoto} in the Go services (an
 * http(s) or site-relative address, not svg, ico, inline data or a known placeholder; JPEG, PNG, WebP and AVIF addresses
 * and addresses without an extension all count). This is the strict test; which addresses a profile counts as a photo
 * (placeholders under {@code allowPlaceholders}) is decided by {@code PictureRules.photoOk}. A page without a photo is
 * never shown as a match when the rules require one: a seller cannot tell it is theirs.
 */
public final class RealPhoto {

    /** What an address points at. SVG, inline and empty addresses are {@link #NONE} under every profile. */
    public enum Kind {
        NONE, PLACEHOLDER, REAL
    }

    private static final List<String> PLACEHOLDER_MARKS = List.of("placeholder", "no-image", "noimage", "no_image",
            "default-image", "coming-soon", "image-not-available", "/icons/", "/icon-");

    private RealPhoto() {
    }

    public static boolean is(String imageUrl) {
        return classify(imageUrl) == Kind.REAL;
    }

    public static Kind classify(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return Kind.NONE;
        }
        String low = imageUrl.strip().toLowerCase(Locale.ROOT);
        if (!low.startsWith("https://") && !low.startsWith("http://") && !low.startsWith("/")) {
            return Kind.NONE;
        }
        String path;
        try {
            path = URI.create(low).getPath();
        } catch (IllegalArgumentException e) {
            return Kind.NONE;
        }
        if (path == null || path.endsWith(".svg") || path.endsWith(".ico")) {
            return Kind.NONE;
        }
        return PLACEHOLDER_MARKS.stream().anyMatch(path::contains) ? Kind.PLACEHOLDER : Kind.REAL;
    }
}
