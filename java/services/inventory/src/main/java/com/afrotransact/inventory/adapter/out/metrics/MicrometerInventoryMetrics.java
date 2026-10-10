package com.afrotransact.inventory.adapter.out.metrics;

import java.util.concurrent.atomic.AtomicLong;

import com.afrotransact.inventory.port.InventoryMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Exposes {@code inventory_reconciliation_drift} and {@code inventory_reservations_total{outcome}} to Prometheus. */
@Component
class MicrometerInventoryMetrics implements InventoryMetrics {

    private final MeterRegistry registry;
    private final AtomicLong drift = new AtomicLong();

    MicrometerInventoryMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("inventory.reconciliation.drift", drift, AtomicLong::doubleValue)
                .description("Units of drift found by the latest ledger/lot reconciliation (0 = consistent)")
                .register(registry);
    }

    @Override
    public void reconciliationDrift(long driftUnits) {
        drift.set(driftUnits);
    }

    @Override
    public void reservationOutcome(String outcome) {
        Counter.builder("inventory.reservations").tag("outcome", outcome)
                .description("Reservation calls by outcome").register(registry).increment();
    }
}
