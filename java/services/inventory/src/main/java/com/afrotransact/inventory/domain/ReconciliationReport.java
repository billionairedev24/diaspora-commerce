package com.afrotransact.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Outcome of recomputing every position from the ledger and from lots. */
public record ReconciliationReport(UUID id, Instant ranAt, int positionsChecked, long driftUnits,
                                   List<PositionDrift> drifts) {

    public ReconciliationReport {
        drifts = List.copyOf(drifts);
    }

    public boolean clean() {
        return driftUnits == 0;
    }
}
