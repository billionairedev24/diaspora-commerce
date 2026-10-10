package com.afrotransact.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Progress of one hide / restore of an organisation's listings, processed page by page (S216).
 *
 * @param eventId   the seller-service enforcement event (one run per event)
 * @param orgId     organisation whose products are republished
 * @param action    hide or restore
 * @param reason    enforcement reason
 * @param status    running, done, or superseded by a newer decision
 * @param cursor    id of the last product processed (keyset cursor; null before the first page)
 * @param processed products processed so far
 * @param pages     pages committed so far
 * @param startedAt when the run started
 * @param updatedAt last committed page
 * @param finishedAt when it finished or was superseded
 * @param version   optimistic lock
 */
public record ListingEnforcementRun(UUID eventId, String orgId, Action action, String reason, Status status,
                                    UUID cursor, int processed, int pages, Instant startedAt, Instant updatedAt,
                                    Instant finishedAt, long version) {

    public ListingEnforcementRun {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(status, "status");
    }

    /** Hide or restore. */
    public enum Action {
        HIDE,
        RESTORE;

        public boolean hides() {
            return this == HIDE;
        }
    }

    /** Run state. */
    public enum Status {
        RUNNING,
        DONE,
        SUPERSEDED
    }

    public static ListingEnforcementRun start(UUID eventId, String orgId, Action action, String reason, Instant now) {
        return new ListingEnforcementRun(eventId, orgId, action, reason, Status.RUNNING, null, 0, 0, now, now, null,
                0);
    }

    public boolean running() {
        return status == Status.RUNNING;
    }

    /**
     * Records one committed page.
     *
     * @param lastProductId id of the last product in the page (null for an empty page)
     * @param count         products in the page
     * @param lastPage      whether the page was shorter than the page size (nothing left)
     */
    public ListingEnforcementRun pageDone(UUID lastProductId, int count, boolean lastPage, Instant now) {
        if (!running()) {
            throw new IllegalStateException("Enforcement run " + eventId + " is " + status);
        }
        UUID next = lastProductId == null ? cursor : lastProductId;
        return new ListingEnforcementRun(eventId, orgId, action, reason, lastPage ? Status.DONE : Status.RUNNING, next,
                processed + count, pages + 1, startedAt, now, lastPage ? now : null, version);
    }

    /** A newer decision for the organisation took over. */
    public ListingEnforcementRun superseded(Instant now) {
        return new ListingEnforcementRun(eventId, orgId, action, reason, Status.SUPERSEDED, cursor, processed, pages,
                startedAt, now, now, version);
    }
}
