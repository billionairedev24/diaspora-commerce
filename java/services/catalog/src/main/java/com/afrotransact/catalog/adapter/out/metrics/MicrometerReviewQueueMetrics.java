package com.afrotransact.catalog.adapter.out.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.afrotransact.catalog.domain.BusinessDays;
import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.port.CatalogSettingsRepository;
import com.afrotransact.catalog.port.ChangeRequestRepository;
import com.afrotransact.catalog.port.GtinExemptionRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The staff review queue (S386): {@code catalog_change_request_open} (change requests and no-barcode exemptions waiting)
 * and {@code catalog_change_request_oldest_age_seconds} (the oldest of them, in calendar seconds), and
 * {@code catalog_change_request_alert_breached} (1 when the oldest waiting request is older than the staff-edited number of
 * business days, 3 by default, Monday to Friday; 0 otherwise). The alert fires on the last one so a request filed on a Friday
 * does not page on Monday, see infra/observability/rules/catalog-review-queue.yaml. All read the database at most once every
 * 15 seconds and report NaN when the read fails (Prometheus treats that as no data).
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
    private volatile double breached = Double.NaN;
    private final CatalogSettingsRepository settings;
    private final int defaultAlertDays;

    MicrometerReviewQueueMetrics(MeterRegistry registry, ChangeRequestRepository changeRequests,
                                 GtinExemptionRepository exemptions, CatalogSettingsRepository settings,
                                 @Value("${at.catalog.review.alert-after-business-days:3}") int defaultAlertDays) {
        this.changeRequests = changeRequests;
        this.exemptions = exemptions;
        this.settings = settings;
        this.defaultAlertDays = defaultAlertDays;
        Gauge.builder("catalog.change.request.alert.breached", this, m -> m.read().breached)
                .description("1 when the oldest request waiting for staff is older than the alert business days")
                .register(registry);
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
                Instant at = Instant.now();
                oldestSeconds = oldest.map(i -> (double) Duration.between(i, at).toSeconds()).orElse(0.0);
                int alertDays = settings.find().map(CatalogPolicy::alertAfterBusinessDays).orElse(defaultAlertDays);
                breached = oldest.map(i -> BusinessDays.add(i, alertDays).isBefore(at) ? 1.0 : 0.0).orElse(0.0);
            } catch (RuntimeException ex) {
                LOG.warn("Could not read the review queue gauges", ex);
                open = Double.NaN;
                oldestSeconds = Double.NaN;
                breached = Double.NaN;
            }
            readAt.set(now);
        }
        return this;
    }
}
