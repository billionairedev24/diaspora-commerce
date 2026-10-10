package com.afrotransact.inventory.application;

/** Nightly lot jobs. Both are idempotent and safe to run on several replicas at once. */
public interface LotLifecycleService {

    /** Marks lots past expiry (node-local date) EXPIRED and blocks them from sale (scheduled, no actor). */
    ExpiryRun expireDueLots();

    /** Recomputes the ≤ 30 / ≤ 60 day markdown flags of sellable lots (scheduled, no actor). */
    int refreshMarkdownFlags();

    /** On-demand run of {@link #expireDueLots()} (inventory:adjust). */
    ExpiryRun expireNow();

    /** On-demand run of {@link #refreshMarkdownFlags()} (inventory:adjust). */
    int markdownNow();

    /**
     * Result of an expiry run.
     *
     * @param deferred lots with units still held by open checkouts; retried on the next run
     */
    record ExpiryRun(int expired, int deferred) {
    }
}
