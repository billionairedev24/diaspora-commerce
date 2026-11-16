package com.afrotransact.module.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** at_module_up: one gauge per module, 1 while the readiness indicator is UP. */
class ModuleUpMeterTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private double gauge(String module) {
        return registry.get(ModuleUpMeter.NAME).tag("module", module).gauge().value();
    }

    @Test
    void isOneWhileTheIndicatorIsUpAndZeroWhenItIsNot() {
        AtomicReference<Health> health = new AtomicReference<>(Health.up().build());
        HealthIndicator indicator = health::get;
        new ModuleUpMeter("catalog", () -> indicator).bindTo(registry);

        assertThat(gauge("catalog")).isEqualTo(1.0);
        health.set(Health.down().build());
        assertThat(gauge("catalog")).isZero();
        health.set(Health.up().build());
        assertThat(gauge("catalog")).isEqualTo(1.0);
    }

    @Test
    void isZeroWhenTheIndicatorCannotBeEvaluated() {
        new ModuleUpMeter("orders", () -> {
            throw new IllegalStateException("module not started");
        }).bindTo(registry);

        assertThat(gauge("orders")).isZero();
    }

    @Test
    void oneGaugePerModuleAndTheMeterFilterAddsTheServiceTag() {
        ModuleMeterFilterTest.ModuleFilterFixture fixture = new ModuleMeterFilterTest.ModuleFilterFixture();
        registry.config().meterFilter(fixture.filter);
        new ModuleUpMeter("catalog", () -> () -> Health.up().build()).bindTo(registry);
        new ModuleUpMeter("tax-returns", () -> () -> Health.down().build()).bindTo(registry);

        assertThat(registry.get(ModuleUpMeter.NAME).tag("module", "catalog").tag("service", "catalog").gauge().value())
                .isEqualTo(1.0);
        assertThat(registry.get(ModuleUpMeter.NAME).tag("module", "tax-returns").tag("service", "tax-returns").gauge()
                .value()).isZero();
        assertThat(registry.find(ModuleUpMeter.NAME).gauges()).hasSize(2);
    }
}
