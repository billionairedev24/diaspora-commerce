package com.afrotransact.payments.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.AdjustmentStatus;
import com.afrotransact.payments.domain.LedgerAdjustment;

/** Proposed, posted and rejected ledger adjustments. */
public interface LedgerAdjustmentRepository {

    void insert(LedgerAdjustment adjustment);

    Optional<LedgerAdjustment> find(UUID id);

    Optional<LedgerAdjustment> lock(UUID id);

    LedgerAdjustment update(LedgerAdjustment adjustment);

    /** Newest first; {@code status} null for every status. */
    List<LedgerAdjustment> list(AdjustmentStatus status, int limit);

    List<LedgerAdjustment> ofItem(UUID itemId);
}
