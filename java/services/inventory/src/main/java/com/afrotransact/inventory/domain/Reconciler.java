package com.afrotransact.inventory.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Compares stored positions with what the ledger and the lots say they should be. */
public interface Reconciler {

    /**
     * @param positions stored levels per position key ({@code sku|nodeId})
     * @param ledger    Σ ledger deltas per position key
     * @param lots      Σ lot quantities per position key (inbound is ignored)
     */
    ReconciliationReport reconcile(UUID id, Instant ranAt, Map<String, StockLevels> positions,
                                   Map<String, StockLevels> ledger, Map<String, StockLevels> lots);

    static String key(String sku, String nodeId) {
        return sku + "|" + nodeId;
    }
}
