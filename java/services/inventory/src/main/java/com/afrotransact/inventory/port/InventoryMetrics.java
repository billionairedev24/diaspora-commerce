package com.afrotransact.inventory.port;

/** Operational metrics exported to Prometheus. */
public interface InventoryMetrics {

    /** Sets the {@code inventory_reconciliation_drift} gauge to the latest run's drift in units. */
    void reconciliationDrift(long driftUnits);

    /** Counts reservation outcomes (held, insufficient, replayed, expired …). */
    void reservationOutcome(String outcome);
}
