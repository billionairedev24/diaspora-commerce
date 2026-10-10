package com.afrotransact.payments.adapter.out.metrics;

import java.util.concurrent.atomic.AtomicLong;

import com.afrotransact.payments.port.PaymentsMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Exposes {@code payments_reconciliation_drift_minor}, {@code payments_webhooks_total{type,outcome}} and
 * {@code payments_transfers_total{outcome}} to Prometheus.
 */
@Component
class MicrometerPaymentsMetrics implements PaymentsMetrics {

    private final MeterRegistry registry;
    private final AtomicLong drift = new AtomicLong();

    MicrometerPaymentsMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("payments.reconciliation.drift.minor", drift, AtomicLong::doubleValue)
                .description("Minor units of drift between ledger 1000 Stripe clearing and Stripe balance "
                        + "transactions found by the latest reconciliation (0 = reconciled)")
                .register(registry);
    }

    @Override
    public void reconciliationDrift(long driftMinor) {
        drift.set(driftMinor);
    }

    @Override
    public void webhook(String type, String outcome) {
        Counter.builder("payments.webhooks").tag("type", type).tag("outcome", outcome)
                .description("Stripe webhook deliveries and processing by outcome").register(registry).increment();
    }

    @Override
    public void transfer(String outcome) {
        Counter.builder("payments.transfers").tag("outcome", outcome)
                .description("Settlement transfers by outcome").register(registry).increment();
    }
}
