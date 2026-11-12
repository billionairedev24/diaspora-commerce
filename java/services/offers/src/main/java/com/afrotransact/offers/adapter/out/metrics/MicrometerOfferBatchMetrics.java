package com.afrotransact.offers.adapter.out.metrics;

import com.afrotransact.offers.port.OfferBatchMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** {@code offers_batch_rows_total{operation,outcome}}: rows the batch endpoints processed. */
@Component
class MicrometerOfferBatchMetrics implements OfferBatchMetrics {

    private final MeterRegistry registry;

    MicrometerOfferBatchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void row(String operation, String outcome) {
        Counter.builder("offers.batch.rows.total").tag("operation", operation).tag("outcome", outcome)
                .description("Rows of bulk offer batches by operation and outcome").register(registry).increment();
    }
}
