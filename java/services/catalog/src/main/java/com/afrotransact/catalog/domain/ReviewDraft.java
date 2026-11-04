package com.afrotransact.catalog.domain;

import java.util.List;

/**
 * What a customer writes: stars, an optional title and text, and up to {@link ProductReview#MAX_PHOTOS} photos.
 * Normalised on construction (blank text becomes null, lists copied).
 */
public record ReviewDraft(int rating, String title, String text, List<ReviewPhoto> photos) {

    public ReviewDraft {
        title = blankToNull(title);
        text = blankToNull(text);
        photos = photos == null ? List.of() : List.copyOf(photos);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
