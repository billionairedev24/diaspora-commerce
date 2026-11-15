package com.afrotransact.module.core;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code at.modules.<name>}: how one module runs inside the core application. Database settings live here (not in the
 * module's own {@code spring.datasource}) because every service uses the same env-var names for them.
 */
public record ModuleProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Database datasource,
        @DefaultValue Migration flyway,
        @DefaultValue Scheduler scheduler,
        @DefaultValue Events events,
        String clientId,
        Set<String> callerScopes) {

    public ModuleProperties {
        callerScopes = callerScopes == null ? Set.of() : Set.copyOf(callerScopes);
    }

    /** @param url empty means the module has no database of its own (mcp-server) */
    public record Database(String url, String username, String password,
                           @DefaultValue("4") int maximumPoolSize,
                           @DefaultValue("0") int minimumIdle,
                           @DefaultValue("10s") Duration connectionTimeout,
                           @DefaultValue("10m") Duration idleTimeout) {
    }

    /** @param locations empty means {@code classpath:db/platform,classpath:modules/<name>/db/migration} */
    public record Migration(@DefaultValue("true") boolean enabled, List<String> locations) {

        public Migration {
            locations = locations == null ? List.of() : List.copyOf(locations);
        }
    }

    public record Scheduler(@DefaultValue("2") int poolSize) {
    }

    public record Events(@DefaultValue("true") boolean relayEnabled, @DefaultValue("200") int relayBatchSize,
                         @DefaultValue("true") boolean retentionEnabled) {
    }
}
