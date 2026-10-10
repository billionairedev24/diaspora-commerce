package com.afrotransact.inventory.domain;

/**
 * Mismatch found by reconciliation for one position.
 *
 * @param position levels stored on the position row
 * @param ledger   levels recomputed from the ledger (Σ deltas)
 * @param lots     levels recomputed from lots (on hand, reserved and held; inbound copied from the position)
 */
public record PositionDrift(String sku, String nodeId, StockLevels position, StockLevels ledger, StockLevels lots) {

    public long driftUnits() {
        return position.distance(ledger) + position.distance(lots);
    }
}
