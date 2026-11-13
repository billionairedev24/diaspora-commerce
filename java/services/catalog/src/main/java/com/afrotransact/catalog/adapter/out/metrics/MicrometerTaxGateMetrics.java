package com.afrotransact.catalog.adapter.out.metrics;

import java.util.concurrent.atomic.AtomicLong;

import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.TaxGateMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Exposes {@code catalog_live_without_tax_decision} (gauge: live listings with a variant that has no resolved tax
 * decision; must always be 0, the alert pages above 0), {@code catalog_tax_gate_closed_total{reason}} and
 * {@code catalog_tax_gate_restored_total}. The gauge reads the database at most once every 15 seconds; when the read
 * fails it reports NaN, which Prometheus treats as no data (the staleness alert fires instead).
 */
@Component
class MicrometerTaxGateMetrics implements TaxGateMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(MicrometerTaxGateMetrics.class);
    private static final long CACHE_MILLIS = 15_000;

    private final MeterRegistry registry;
    private final ProductRepository products;
    private final AtomicLong readAt = new AtomicLong();
    private volatile double cached = Double.NaN;
    private final AtomicLong lastAuthorityCheck = new AtomicLong();

    MicrometerTaxGateMetrics(MeterRegistry registry, ProductRepository products) {
        this.registry = registry;
        this.products = products;
        Gauge.builder("catalog.live.without.tax.decision", this, MicrometerTaxGateMetrics::liveWithoutDecision)
                .description("Live listings with a variant that has no stored, resolved tax decision (must be 0)")
                .register(registry);
        Gauge.builder("catalog.tax.gate.authority.last.check.timestamp.seconds", lastAuthorityCheck, AtomicLong::get)
                .description("Unix time of the last successful check of live listings against tax-returns (0 = never)")
                .register(registry);
    }

    private synchronized double liveWithoutDecision() {
        long now = System.currentTimeMillis();
        if (now - readAt.get() >= CACHE_MILLIS) {
            try {
                cached = products.countLiveWithoutTaxDecision();
            } catch (RuntimeException ex) {
                LOG.warn("Could not count live listings without a tax decision", ex);
                cached = Double.NaN;
            }
            readAt.set(now);
        }
        return cached;
    }

    @Override
    public void closed(String reasonCode) {
        Counter.builder("catalog.tax.gate.closed").tag("reason", reasonCode)
                .description("Live listings closed because a variant had no resolved tax decision").register(registry)
                .increment();
    }

    @Override
    public void restored() {
        Counter.builder("catalog.tax.gate.restored").description("Listings held for tax that went back on sale")
                .register(registry).increment();
    }

    @Override
    public void disagreement(String reasonCode) {
        Counter.builder("catalog.tax.gate.disagreement").tag("reason", reasonCode)
                .description("Live listings whose own tax state said OK but tax-returns' stored decision is not RESOLVED")
                .register(registry).increment();
    }

    @Override
    public void authorityUnreachable() {
        Counter.builder("catalog.tax.gate.authority.unreachable")
                .description("Checks against tax-returns that failed; the state of the listings was kept")
                .register(registry).increment();
    }

    @Override
    public void mediaUnavailable() {
        Counter.builder("catalog.tax.gate.media.unavailable")
                .description("Held-photo checks skipped during tax repair because media could not be asked")
                .register(registry).increment();
    }

    @Override
    public void authorityChecked() {
        lastAuthorityCheck.set(System.currentTimeMillis() / 1000);
    }
}
