package com.afrotransact.catalog.application;

import java.time.Duration;
import java.util.Objects;

import com.afrotransact.catalog.domain.imports.ImportLimits;
import com.afrotransact.catalog.domain.imports.ImportMode;

/**
 * Bulk upload job (S308) and its limits (S393 to S396).
 *
 * @param notifyMinRows    uploads with at least this many rows notify the seller when the import completes
 * @param stallAfter       a queued or running import without progress for this long is resumed by the background job
 * @param maxRows          rows in a create, update or offer upload (5,000)
 * @param maxFeedRows      rows in a price and quantity feed or a stock file (50,000)
 * @param maxUploadsPerDay uploads one organisation may make in 24 hours, every mode together (20)
 */
public record ImportSettings(int notifyMinRows, Duration stallAfter, int maxRows, int maxFeedRows,
                             int maxUploadsPerDay) {

    public ImportSettings {
        Objects.requireNonNull(stallAfter, "stallAfter");
    }

    /** The default limits. */
    public ImportSettings(int notifyMinRows, Duration stallAfter) {
        this(notifyMinRows, stallAfter, ImportLimits.MAX_ROWS, ImportLimits.MAX_FEED_ROWS, 20);
    }

    /** Rows an upload of this mode may have. */
    public int rowCap(ImportMode mode) {
        return mode == ImportMode.PRICE_QTY ? maxFeedRows : maxRows;
    }

    /** Bytes a file of this mode may have. */
    public static long byteCap(ImportMode mode) {
        return mode == ImportMode.PRICE_QTY ? ImportLimits.MAX_FEED_BYTES : ImportLimits.MAX_FILE_BYTES;
    }
}
