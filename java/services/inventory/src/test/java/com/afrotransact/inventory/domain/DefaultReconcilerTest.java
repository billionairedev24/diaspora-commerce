package com.afrotransact.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DefaultReconcilerTest {

    private final Reconciler reconciler = new DefaultReconciler();

    @Test
    void consistentPositionsHaveZeroDrift() {
        Map<String, StockLevels> positions = Map.of("A|HOU-FC", new StockLevels(20, 5, 2, 10));
        Map<String, StockLevels> ledger = Map.of("A|HOU-FC", new StockLevels(20, 5, 2, 10));
        Map<String, StockLevels> lots = Map.of("A|HOU-FC", new StockLevels(20, 5, 2, 0));

        ReconciliationReport r = reconciler.reconcile(UUID.randomUUID(), Instant.now(), positions, ledger, lots);

        assertThat(r.clean()).isTrue();
        assertThat(r.positionsChecked()).isEqualTo(1);
    }

    @Test
    void reportsDriftAgainstLedgerAndLots() {
        Map<String, StockLevels> positions = Map.of("A|HOU-FC", new StockLevels(20, 5, 0, 0),
                "B|EWR-FC", new StockLevels(4, 0, 0, 0));
        Map<String, StockLevels> ledger = Map.of("A|HOU-FC", new StockLevels(18, 5, 0, 0),
                "B|EWR-FC", new StockLevels(4, 0, 0, 0));
        Map<String, StockLevels> lots = Map.of("A|HOU-FC", new StockLevels(20, 5, 0, 0),
                "B|EWR-FC", new StockLevels(4, 0, 0, 0), "C|ATL-FC", new StockLevels(1, 0, 0, 0));

        ReconciliationReport r = reconciler.reconcile(UUID.randomUUID(), Instant.now(), positions, ledger, lots);

        assertThat(r.driftUnits()).isEqualTo(3);
        assertThat(r.drifts()).extracting(PositionDrift::sku).containsExactly("A", "C");
    }
}
