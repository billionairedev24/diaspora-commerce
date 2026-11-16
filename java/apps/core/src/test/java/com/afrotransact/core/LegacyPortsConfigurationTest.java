package com.afrotransact.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.mock.env.MockEnvironment;

/** The old Java ports are compatibility only: opened by {@code at.core.legacy-ports.enabled=true} (local), never by default. */
class LegacyPortsConfigurationTest {

    private static TomcatServletWebServerFactory customised(MockEnvironment environment) {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        new LegacyPortsConfiguration().legacyPortsCustomizer(environment).customize(factory);
        return factory;
    }

    private static MockEnvironment ports() {
        return new MockEnvironment().withProperty("at.core.module-ports.8101", "catalog")
                .withProperty("at.core.module-ports.8104", "orders");
    }

    @Test
    void noExtraListenerByDefaultEvenWhenPortsAreConfigured() {
        assertThat(customised(ports()).getAdditionalConnectors()).isEmpty();
    }

    @Test
    void enabledOpensEveryConfiguredPort() {
        assertThat(customised(ports().withProperty("at.core.legacy-ports.enabled", "true")).getAdditionalConnectors())
                .extracting(c -> c.getPort()).containsExactlyInAnyOrder(8101, 8104);
    }

    @Test
    void explicitlyDisabledOpensNone() {
        assertThat(customised(ports().withProperty("at.core.legacy-ports.enabled", "false")).getAdditionalConnectors())
                .isEmpty();
    }
}
