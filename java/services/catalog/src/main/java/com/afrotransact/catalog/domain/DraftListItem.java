package com.afrotransact.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the drafts table: the draft without its payload, plus how much of the basics is still to do.
 *
 * @param todo how many of the {@link #CHECKS} basics (category, title, a photo, a price, a description) the saved
 *             payload still lacks; 0 means ready to review and submit
 */
public record DraftListItem(UUID id, UUID productId, String title, String categoryPath, Instant updatedAt, long version,
                            int todo) {

    /** The number of basics {@link #todo()} counts. */
    public static final int CHECKS = 5;
}
