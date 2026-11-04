package com.afrotransact.seller.application;

import java.util.List;

/** Outcome of a staff migration: organisations moved and the ones that failed (with Stripe's error). */
public record MigrationResult(String planCode, int toVersion, List<String> migrated, List<Failure> failed) {

    public MigrationResult {
        migrated = List.copyOf(migrated);
        failed = List.copyOf(failed);
    }

    /** One organisation that could not be moved. */
    public record Failure(String orgId, String error) {
    }
}
