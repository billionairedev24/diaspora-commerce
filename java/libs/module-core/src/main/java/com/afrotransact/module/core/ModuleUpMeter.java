package com.afrotransact.module.core;

import java.util.function.Supplier;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * Exports {@code at_module_up{module,service}}: 1 while the module's readiness indicator ({@link ModuleHealthIndicator}) is UP,
 * 0 when it is not UP or cannot be evaluated. One gauge per mounted module; the {@code ModuleNotReady} alert and the Release 1
 * modules dashboard read it. It deliberately does not ping the module's database (that is {@link ModuleDatabaseHealthIndicator},
 * in the module's own health group), so scraping never opens a connection.
 */
final class ModuleUpMeter implements MeterBinder {

    static final String NAME = "at.module.up";

    private final String module;
    private final Supplier<HealthIndicator> indicator;

    ModuleUpMeter(String module, Supplier<HealthIndicator> indicator) {
        this.module = module;
        this.indicator = indicator;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(NAME, this, ModuleUpMeter::value).tag("module", module).strongReference(true)
                .description("1 when the module's readiness indicator is UP, 0 otherwise").register(registry);
    }

    double value() {
        try {
            return Status.UP.equals(indicator.get().health().getStatus()) ? 1 : 0;
        } catch (RuntimeException ex) {
            return 0;
        }
    }
}
