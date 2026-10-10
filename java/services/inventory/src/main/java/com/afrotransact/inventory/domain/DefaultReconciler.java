package com.afrotransact.inventory.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/** Reports every position whose stored levels differ from the ledger sums or the lot sums. */
public class DefaultReconciler implements Reconciler {

    @Override
    public ReconciliationReport reconcile(UUID id, Instant ranAt, Map<String, StockLevels> positions,
                                          Map<String, StockLevels> ledger, Map<String, StockLevels> lots) {
        TreeSet<String> keys = new TreeSet<>(positions.keySet());
        keys.addAll(ledger.keySet());
        keys.addAll(lots.keySet());
        List<PositionDrift> drifts = new ArrayList<>();
        long total = 0;
        for (String key : keys) {
            StockLevels stored = positions.getOrDefault(key, StockLevels.ZERO);
            StockLevels fromLedger = ledger.getOrDefault(key, StockLevels.ZERO);
            StockLevels fromLots = lots.getOrDefault(key, StockLevels.ZERO);
            StockLevels lotsWithInbound = new StockLevels(fromLots.onHand(), fromLots.reserved(), fromLots.damaged(),
                    stored.inbound());
            String[] parts = key.split("\\|", 2);
            PositionDrift drift = new PositionDrift(parts[0], parts.length > 1 ? parts[1] : "", stored, fromLedger,
                    lotsWithInbound);
            if (drift.driftUnits() > 0) {
                drifts.add(drift);
                total += drift.driftUnits();
            }
        }
        return new ReconciliationReport(id, ranAt, keys.size(), total, drifts);
    }
}
