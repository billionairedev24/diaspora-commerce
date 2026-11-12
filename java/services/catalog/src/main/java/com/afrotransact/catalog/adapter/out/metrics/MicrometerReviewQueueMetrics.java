package com.afrotransact.catalog.adapter.out.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.afrotransact.catalog.port.ChangeRequestRepository;
import com.afrotransact.catalog.port.GtinExemptionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The staff review queue (S386): {@code catalog_change_request_open} (change requests and no-barcode exemptions waiting)
 * and {@code catalog_change_request_oldest_age_seconds} (the oldest of them; the alert fires when it is over three
 * business days, see infra/observability). Both read the database at most once every 15 seconds and report NaN when the
 * read fails (Prometheus treats that as no data).
 */
@Component
class MicrometerReviewQueueMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(MicrometerReviewQueueMetrics.class);
    private static final long CACHE_MILLIS = 15_000;

    private final ChangeRequestRepository changeRequests;
    private final GtinExemptionRepository exemptions;
    private final AtomicLong readAt = new AtomicLong();
    private volatile double open = Double.NaN;
    private volatile double oldestSeconds = Double.NaN;

    MicrometerReviewQueueMetrics(MeterRegistry registry, ChangeRequestRepository changeRequests,
                                 GtinExemptionRepository exemptions) {
        this.changeRequests = changeRequests;
        this.exemptions = exemptions;
        Gauge.builder("catalog.change.request.open", this, m -> m.read().open)
                .description("Change requests and no-barcode exemptions waiting for staff").register(registry);
        Gauge.builder("catalog.change.request.oldest.age.seconds", this, m -> m.read().oldestSeconds)
                .description("Age of the oldest request waiting for staff, in seconds (0 when none waits)")
                .register(registry);
    }

    private synchronized MicrometerReviewQueueMetrics read() {
        long now = System.currentTimeMillis();
        if (now - readAt.get() >= CACHE_MILLIS) {
            try {
                open = (double) changeRequests.countOpen() + exemptions.countOpen();
                Optional<Instant> oldest = changeRequests.oldestOpenSince();
                Optional<Instant> oldestExemption = exemptions.oldestOpenSince();
                if (oldestExemption.isPresent() && (oldest.isEmpty() || oldestExemption.get().isBefore(oldest.get()))) {
                    oldest = oldestExemption;
                }
                oldestSeconds = oldest.map(i -> (double) Duration.between(i, Instant.now()).toSeconds()).orElse(0.0);
            } catch (RuntimeException ex) {
                LOG.warn("Could not read the review queue gauges", ex);
                open = Double.NaN;
                oldestSeconds = Double.NaN;
            }
            readAt.set(now);
        }
        return this;
    }
}
