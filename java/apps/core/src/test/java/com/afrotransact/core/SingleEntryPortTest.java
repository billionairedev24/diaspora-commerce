package com.afrotransact.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * In dev, uat and prod afrotransact-core exposes ONE port: its own configuration files (application.yml and the profile's file, the
 * way Spring Boot loads them at start, no database needed) leave {@code at.core.legacy-ports.enabled} off, name no extra listener and no
 * separate management port, so the web server factory ends up with the main connector only. The old per-module Java ports (8100 to 8115)
 * are compatibility only and are opened by the {@code local} profile alone; that is held here too, so a change of either side is seen.
 */
class SingleEntryPortTest {

    private static StandardEnvironment environment(String profile) {
        StandardEnvironment environment = new StandardEnvironment();
        // nothing from the machine's own environment may change the answer
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        ConfigDataEnvironmentPostProcessor.applyTo(environment, new DefaultResourceLoader(), new DefaultBootstrapContext(), profile);
        return environment;
    }

    private static TomcatServletWebServerFactory factoryFor(StandardEnvironment environment) {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(
                environment.getProperty("server.port", Integer.class, 8080));
        new LegacyPortsConfiguration().legacyPortsCustomizer(environment).customize(factory);
        return factory;
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "uat", "prod"})
    void deployedProfilesExposeOnlyTheSingleEntryPort(String profile) {
        StandardEnvironment env = environment(profile);

        assertThat(env.getProperty("at.core.legacy-ports.enabled", Boolean.class, false)).as("legacy ports flag").isFalse();
        assertThat(env.getProperty("server.port", Integer.class)).as("the one entry port").isEqualTo(8080);
        assertThat(env.getProperty("management.server.port")).as("no separate management port").isNull();
        assertThat(Binder.get(env).bind("at.core.module-ports", Bindable.mapOf(Integer.class, String.class)).orElse(Map.of()))
                .as("no module ports configured outside local").isEmpty();

        TomcatServletWebServerFactory factory = factoryFor(env);
        assertThat(factory.getAdditionalConnectors()).as("extra listeners").isEmpty();
    }

    @Test
    void onlyTheLocalProfileOpensTheLegacyPorts() {
        StandardEnvironment env = environment("local");
        assertThat(env.getProperty("at.core.legacy-ports.enabled", Boolean.class, false)).isTrue();
        assertThat(factoryFor(env).getAdditionalConnectors()).extracting(c -> c.getPort())
                .contains(8100, 8101, 8104, 8114, 8115).doesNotContain(8120);
    }
}
