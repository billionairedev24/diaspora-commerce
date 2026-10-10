package com.afrotransact.ai.domain.moderation;

/** Outcome of an image moderation check, from least to most restrictive. */
public enum ImageDecision {
    /** Fine to publish. */
    ALLOW,
    /** A person should look before it is published (watermark, contact details, not a product photo …). */
    REVIEW,
    /** Prohibited content; never published. */
    BLOCK;

    /** The more restrictive of the two. */
    public ImageDecision atLeast(ImageDecision other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
