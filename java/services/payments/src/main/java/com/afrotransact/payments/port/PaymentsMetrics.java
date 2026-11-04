package com.afrotransact.payments.port;

/** Business metrics exported to Prometheus. */
public interface PaymentsMetrics {

    /** {@code payments_reconciliation_drift_minor}: drift found by the latest reconciliation. */
    void reconciliationDrift(long driftMinor);

    void webhook(String type, String outcome);

    void transfer(String outcome);

    /**
     * {@code payments_transfer_reversals_refused_total{code}}: Stripe definitively refused to reverse a seller's
     * transfer for a refund (the amount became a transfer recovery the seller owes the platform).
     */
    void transferReversalRefused(String stripeCode);

    /** {@code payments_transfer_recoveries_total{outcome}}: opened, settled (from earnings), received, written_off. */
    void transferRecovery(String outcome);
}
