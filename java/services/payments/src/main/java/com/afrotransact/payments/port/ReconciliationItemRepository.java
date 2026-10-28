package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.ReconciliationItem;
import com.afrotransact.payments.domain.ReconciliationStatus;

/** The differences a reconciliation run found, tracked until resolved. */
public interface ReconciliationItemRepository {

    void insert(ReconciliationItem item);

    Optional<ReconciliationItem> find(UUID id);

    Optional<ReconciliationItem> lock(UUID id);

    ReconciliationItem update(ReconciliationItem item);

    /** Newest first; {@code status} null for every status. */
    List<ReconciliationItem> list(ReconciliationStatus status, int limit);

    /** Open and explained items of earlier runs of the same day window (the ones a new run replaces). */
    List<ReconciliationItem> unresolvedOfWindow(Instant windowStart);

    /** Items of one run. */
    List<ReconciliationItem> ofRun(UUID runId);
}
