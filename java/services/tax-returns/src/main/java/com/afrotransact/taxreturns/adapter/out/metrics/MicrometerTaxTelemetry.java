package com.afrotransact.taxreturns.adapter.out.metrics;

import com.afrotransact.taxreturns.domain.shadow.ShadowKind;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** {@link TaxTelemetry} over Micrometer (Prometheus: {@code tax_*_total}). */
@Component
class MicrometerTaxTelemetry implements TaxTelemetry {

    private final MeterRegistry registry;

    MicrometerTaxTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void resolved(String status) {
        registry.counter("tax.classification.resolve", "status", status).increment();
    }

    @Override
    public void quoteLine(String status, String reason) {
        registry.counter("tax.quote.lines", "status", status, "reason", reason).increment();
    }

    @Override
    public void providerFallback(String provider, String why, int lines) {
        registry.counter("tax.provider.fallback", "provider", provider, "why", why).increment();
        registry.counter("tax.provider.fallback.lines", "provider", provider, "why", why).increment(lines);
    }

    @Override
    public void shadow(TaxProviderId provider, ShadowKind kind) {
        registry.counter("tax.shadow.lines", "provider", provider.name(), "kind", kind.name()).increment();
    }

    @Override
    public void shadowFailure(TaxProviderId provider, String why) {
        registry.counter("tax.shadow.failures", "provider", provider.name(), "why", why).increment();
    }

    @Override
    public void recordChange(String kind) {
        registry.counter("tax.classification.changes", "kind", kind).increment();
    }
}
