package com.afrotransact.module.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Per-edge timeouts keep what each HTTP adapter used; the bulkhead follows the callee's pool. */
class ModuleCallSettingsTest {

    private final MockEnvironment environment = new MockEnvironment();
    private final ModuleRegistry registry = new ModuleRegistry("afrotransact-core");
    private final ModuleCallSettings settings = new ModuleCallSettings(environment, registry);

    @Test
    void anEdgeThatIsNotListedGetsFiveSecondsAndAnAiEdgeTen() {
        assertThat(settings.timeout("orders", "inventory", null)).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.timeout("orders", "payments", null)).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.timeout("catalog", "ai-gateway", null)).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.timeout("ai-gateway", "payments", null)).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.timeout("unspecified", "unspecified", null)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void thePackagedDefaultsAreTheHttpAdaptersOwnReadTimeouts() {
        assertThat(settings.timeout("orders", "tax-returns", null)).isEqualTo(Duration.ofSeconds(2));
        assertThat(settings.timeout("payments", "seller", null)).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.timeout("catalog", "offers", null)).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.timeout("catalog", "offers", "batch")).isEqualTo(Duration.ofSeconds(60));
        assertThat(settings.timeout("catalog", "offers", "unknown-op")).isEqualTo(Duration.ofSeconds(3));
        assertThat(settings.timeout("identity", "seller", null)).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.timeout("bookings", "payments", null)).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.timeout("seller", "ai-gateway", null)).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.timeout("trust-ops", "ai-gateway", null)).isEqualTo(Duration.ofSeconds(10));
        // Lola's readers: at.ai.downstream.timeout was 5 s, so a tool degrades after 5 s although ai-gateway is on one side
        for (String callee : new String[] {"catalog", "offers", "orders", "bookings", "tax-returns"}) {
            assertThat(settings.timeout("ai-gateway", callee, null)).as("ai-gateway -> " + callee).isEqualTo(Duration.ofSeconds(5));
        }
    }

    @Test
    void anEdgePropertyOverridesWithModuleNamesWithoutDashes() {
        environment.setProperty("at.taxreturns.inventory.timeout", "15s");
        environment.setProperty("at.catalog.offers.batch-timeout", "2m");
        assertThat(settings.timeout("tax-returns", "inventory", null)).isEqualTo(Duration.ofSeconds(15));
        assertThat(settings.timeout("catalog", "offers", "batch")).isEqualTo(Duration.ofMinutes(2));
        assertThat(settings.timeout("catalog", "offers", null)).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void theCallersOldUpstreamTimeoutStillAppliesToItsEdges() {
        environment.setProperty("at.orders.upstreams.timeout", "7s");
        assertThat(settings.timeout("orders", "inventory", null)).isEqualTo(Duration.ofSeconds(7));
        environment.setProperty("at.orders.inventory.timeout", "1s");
        assertThat(settings.timeout("orders", "inventory", null)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void theBulkheadIsTwiceTheCalleesPoolWithAFloorAndCanBeSet() {
        assertThat(settings.concurrency("unmounted")).isEqualTo(8);
        registry.add(new ModuleDefinition("catalog", "catalog", "com.afrotransact.catalog", "x.C", null),
                new ModuleProperties(true, new ModuleProperties.Database(null, null, null, 8, 0, Duration.ofSeconds(10),
                        Duration.ofMinutes(10)), null, null, null, null, null));
        assertThat(settings.concurrency("catalog")).isEqualTo(16);
        environment.setProperty("at.modules.catalog.call-concurrency", "3");
        assertThat(settings.concurrency("catalog")).isEqualTo(3);
    }
}
