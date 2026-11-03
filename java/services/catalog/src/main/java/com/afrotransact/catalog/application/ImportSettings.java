package com.afrotransact.catalog.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Bulk upload job (S308).
 *
 * @param notifyMinRows uploads with at least this many rows notify the seller when the import completes
 * @param stallAfter    a queued or running import without progress for this long is resumed by the background job
 */
public record ImportSettings(int notifyMinRows, Duration stallAfter) {

    public ImportSettings {
        Objects.requireNonNull(stallAfter, "stallAfter");
    }
}
