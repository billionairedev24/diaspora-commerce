package com.afrotransact.catalog.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Paged hide / restore (S216).
 *
 * @param pageSize   products republished per transaction
 * @param stallAfter a running run whose last page is older than this is resumed by the background job
 */
public record ListingEnforcementSettings(int pageSize, Duration stallAfter) {

    public ListingEnforcementSettings {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        Objects.requireNonNull(stallAfter, "stallAfter");
    }
}
