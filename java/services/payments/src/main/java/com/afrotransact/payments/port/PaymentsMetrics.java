package com.afrotransact.payments.port;

/** Business metrics exported to Prometheus. */
public interface PaymentsMetrics {

    /** {@code payments_reconciliation_drift_minor}: drift found by the latest reconciliation. */
    void reconciliationDrift(long driftMinor);

    void webhook(String type, String outcome);

    void transfer(String outcome);
}
