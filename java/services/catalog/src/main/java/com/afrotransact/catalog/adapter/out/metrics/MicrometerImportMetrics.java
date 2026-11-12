package com.afrotransact.catalog.adapter.out.metrics;

import com.afrotransact.catalog.port.ImportMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@code catalog_import_uploads_total{mode}}, {@code catalog_import_rows_total{mode,outcome}} and
 * {@code catalog_import_refused_total{mode,reason}}. Low cardinality by design: no organisation, SKU, ATIN or file name.
 */
@Component
class MicrometerImportMetrics implements ImportMetrics {

    private final MeterRegistry registry;

    MicrometerImportMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void uploaded(String mode) {
        Counter.builder("catalog.import.uploads").tag("mode", mode).description("Bulk uploads stored, by mode")
                .register(registry).increment();
    }

    @Override
    public void row(String mode, String outcome) {
        Counter.builder("catalog.import.rows").tag("mode", mode).tag("outcome", outcome)
                .description("Bulk upload rows finished, by mode and outcome").register(registry).increment();
    }

    @Override
    public void refused(String mode, String reason) {
        Counter.builder("catalog.import.refused").tag("mode", mode).tag("reason", reason)
                .description("Bulk uploads refused before storing").register(registry).increment();
    }
}
