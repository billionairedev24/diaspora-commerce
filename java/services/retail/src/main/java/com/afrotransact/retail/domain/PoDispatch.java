package com.afrotransact.retail.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Delivery of a sent PO to the supplier over one channel (S225), worked off by the dispatch job with retries.
 *
 * @param recipient      contact email (EMAIL) or ISA interchange id (EDI)
 * @param assetId        media asset of the PO PDF (EMAIL)
 * @param linkExpiresAt  when the emailed signed link stops working (EMAIL)
 * @param controlNumber  ISA control number of the transmitted 850 (EDI)
 * @param transmissionId the transport's receipt (file name or AS2 message id) (EDI)
 */
public record PoDispatch(UUID id, UUID poId, String poNumber, DispatchChannel channel, DispatchStatus status,
                         int attempts, Instant nextAttemptAt, String lastError, String recipient, UUID assetId,
                         Instant linkExpiresAt, String controlNumber, String transmissionId, Instant createdAt,
                         Instant updatedAt, long version) {

    private static final Duration FIRST_RETRY = Duration.ofMinutes(1);
    private static final Duration MAX_RETRY = Duration.ofHours(1);

    public PoDispatch {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(poId, "poId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
    }

    public static PoDispatch pending(UUID poId, String poNumber, DispatchChannel channel, String recipient,
                                     Instant now) {
        return new PoDispatch(UUID.randomUUID(), poId, poNumber, channel, DispatchStatus.PENDING, 0, now, null,
                recipient, null, null, null, null, now, now, 0);
    }

    /** The PDF was stored and the email requested. */
    public PoDispatch emailed(String to, UUID pdfAssetId, Instant linkExpiry, Instant now) {
        return new PoDispatch(id, poId, poNumber, channel, DispatchStatus.DONE, attempts + 1, now, null, to,
                pdfAssetId, linkExpiry, controlNumber, transmissionId, createdAt, now, version);
    }

    /** The 850 was accepted by the transport. */
    public PoDispatch transmitted(String interchangeId, String control, String receipt, Instant now) {
        return new PoDispatch(id, poId, poNumber, channel, DispatchStatus.DONE, attempts + 1, now, null,
                interchangeId, assetId, linkExpiresAt, control, receipt, createdAt, now, version);
    }

    /**
     * A failed attempt: retried with exponential backoff (1 min, 2 min … at most 1 h) until {@code maxAttempts},
     * or failed at once when {@code permanent}.
     */
    public PoDispatch attemptFailed(String error, boolean permanent, int maxAttempts, Instant now) {
        int n = attempts + 1;
        boolean giveUp = permanent || n >= maxAttempts;
        Duration wait = FIRST_RETRY.multipliedBy(1L << Math.min(n - 1, 10));
        Instant next = giveUp ? now : now.plus(wait.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : wait);
        return new PoDispatch(id, poId, poNumber, channel, giveUp ? DispatchStatus.FAILED : DispatchStatus.PENDING,
                n, next, error, recipient, assetId, linkExpiresAt, controlNumber, transmissionId, createdAt, now,
                version);
    }

    /** A buyer retries a failed dispatch (after fixing the contact or the EDI settings). */
    public PoDispatch retry(Instant now) {
        if (status != DispatchStatus.FAILED) {
            throw new RetailStateConflict("retail.dispatch-not-failed", channel + " dispatch of " + poNumber
                    + " is " + status + "; only failed dispatches can be retried");
        }
        return new PoDispatch(id, poId, poNumber, channel, DispatchStatus.PENDING, 0, now, lastError, recipient,
                assetId, linkExpiresAt, controlNumber, transmissionId, createdAt, now, version);
    }
}
