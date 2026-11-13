package com.afrotransact.catalog.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** S396: bulk upload metrics are low cardinality: a fixed set of tag keys, each with a small closed set of values. */
class MicrometerImportMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerImportMetrics metrics = new MicrometerImportMetrics(registry);

    @Test
    void theNumberOfMetricSeriesIsBoundedByTheModesAndOutcomesWhateverTheTraffic() {
        for (int org = 0; org < 50; org++) {
            for (ImportMode mode : ImportMode.values()) {
                metrics.uploaded(mode.name());
                for (RowOutcome outcome : RowOutcome.values()) {
                    metrics.row(mode.name(), outcome.name());
                }
                metrics.refused(mode.name(), "ACTIVE_UPLOAD");
                metrics.refused(mode.name(), "DAILY_LIMIT");
            }
        }

        List<Meter> meters = registry.getMeters();
        assertThat(meters).hasSize(ImportMode.values().length * (1 + RowOutcome.values().length + 2));
        assertThat(meters).allSatisfy(m -> assertThat(m.getId().getName()).startsWith("catalog.import."));
        Set<String> keys = meters.stream().flatMap(m -> m.getId().getTags().stream()).map(Tag::getKey).collect(Collectors.toSet());
        assertThat(keys).containsExactlyInAnyOrder("mode", "outcome", "reason");
        Set<String> modes = meters.stream().flatMap(m -> m.getId().getTags().stream()).filter(t -> t.getKey().equals("mode"))
                .map(Tag::getValue).collect(Collectors.toSet());
        assertThat(modes).isSubsetOf(List.of("CREATE", "UPDATE", "OFFER", "PRICE_QTY"));
        assertThat(registry.get("catalog.import.uploads").tag("mode", "OFFER").counter().count()).isEqualTo(50);
    }
}
