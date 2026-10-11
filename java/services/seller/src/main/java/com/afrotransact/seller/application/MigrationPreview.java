package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.domain.Cadence;

/** Subscribers on older versions of a plan and what the current version would charge them. */
public record MigrationPreview(String planCode, int toVersion, List<Row> rows) {

    public MigrationPreview {
        rows = List.copyOf(rows);
    }

    /**
     * One organisation.
     *
     * @param effectiveAt their next renewal, when the new price first applies
     */
    public record Row(String orgId, String orgName, int fromVersion, Cadence cadence, long fromCents, long toCents,
                      String status, Instant effectiveAt) {
    }
}
