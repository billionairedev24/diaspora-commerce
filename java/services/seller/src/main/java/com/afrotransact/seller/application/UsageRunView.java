package com.afrotransact.seller.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Outcome of one metering run.
 *
 * @param status        COMPLETED, PARTIAL (a source or some reports failed; the next run catches up) or FAILED
 * @param rowsChanged   (organisation, meter, day) rows created or changed from the sources
 * @param reported      meter events sent to Stripe
 * @param pending       rows still to report (no Stripe customer yet, Stripe refused or unavailable, too old)
 * @param corrections   rows whose usage dropped below what was already reported
 * @param failedSources usage sources that could not be read (STORAGE, ADS, AI)
 */
public record UsageRunView(UUID id, LocalDate from, LocalDate to, String trigger, String status, int rowsChanged,
                           int reported, int pending, int corrections, List<String> failedSources,
                           Instant startedAt, Instant finishedAt) {

    public UsageRunView {
        failedSources = List.copyOf(failedSources);
    }
}
