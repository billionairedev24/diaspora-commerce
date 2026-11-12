package com.afrotransact.catalog.adapter.out.metrics;

import com.afrotransact.catalog.port.MatchMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@code catalog_match_total{result}}: how often sellers find an existing page (MATCH), have to create one (NO_MATCH) or
 * mistype (INVALID, RESTRICTED). Never a barcode or an organisation.
 */
@Component
class MicrometerMatchMetrics implements MatchMetrics {

    private final MeterRegistry registry;

    MicrometerMatchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void matched(String result) {
        Counter.builder("catalog.match").tag("result", result)
                .description("Catalog match lookups by outcome").register(registry).increment();
    }
}
