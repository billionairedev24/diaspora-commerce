package com.afrotransact.offers.adapter.out.metrics;

import java.util.concurrent.atomic.AtomicLong;

import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.offers.port.OfferTaxMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Exposes {@code offers_active_without_tax_decision} (gauge: ACTIVE offers that carry no tax basis; must always be 0, the
 * alert pages above 0), {@code offers_tax_held} (gauge: offers waiting for a decision), {@code
 * offers_tax_held_total{reason}} and {@code offers_tax_released_total}. The gauges read the database at most once every 15
 * seconds; when the read fails they report NaN, which Prometheus treats as no data.
 */
@Component
class MicrometerOfferTaxMetrics implements OfferTaxMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(MicrometerOfferTaxMetrics.class);
    private static final long CACHE_MILLIS = 15_000;

    private final MeterRegistry registry;
    private final OfferRepository offers;
    private final AtomicLong readAt = new AtomicLong();
    private volatile double withoutBasis = Double.NaN;
    private volatile double held = Double.NaN;

    MicrometerOfferTaxMetrics(MeterRegistry registry, OfferRepository offers) {
        this.registry = registry;
        this.offers = offers;
        Gauge.builder("offers.active.without.tax.decision", this, m -> m.read().withoutBasis)
                .description("Active offers without a tax basis: the seller has no verified RESOLVED tax decision (must be 0)")
                .register(registry);
        Gauge.builder("offers.tax.held", this, m -> m.read().held)
                .description("Offers waiting for the seller's tax decision (TAX_HELD)").register(registry);
    }

    private synchronized MicrometerOfferTaxMetrics read() {
        long now = System.currentTimeMillis();
        if (now - readAt.get() >= CACHE_MILLIS) {
            try {
                withoutBasis = offers.countActiveWithoutTaxBasis();
                held = offers.countTaxHeld();
            } catch (RuntimeException ex) {
                LOG.warn("Could not count offers without a tax decision", ex);
                withoutBasis = Double.NaN;
                held = Double.NaN;
            }
            readAt.set(now);
        }
        return this;
    }

    @Override
    public void held(String reason) {
        Counter.builder("offers.tax.held.total").tag("reason", reason)
                .description("Offers held because the seller has no resolved tax decision").register(registry).increment();
    }

    @Override
    public void released() {
        Counter.builder("offers.tax.released.total").description("Held offers released after the seller's decision resolved")
                .register(registry).increment();
    }
}
