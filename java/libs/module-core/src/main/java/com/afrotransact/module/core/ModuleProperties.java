package com.afrotransact.module.core;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code at.modules.<name>}: how one module runs inside the core application. Database settings live here (not in the
 * module's own {@code spring.datasource}) because every service uses the same env-var names for them.
 */
public record ModuleProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Database datasource,
        @DefaultValue Isolation database,
        @DefaultValue Migration flyway,
        @DefaultValue Scheduler scheduler,
        @DefaultValue Events events,
        String clientId,
        Set<String> callerScopes) {

    @ConstructorBinding
    public ModuleProperties {
        database = database == null ? new Isolation(Isolation.Mode.SCHEMA, null) : database;
        callerScopes = callerScopes == null ? Set.of() : Set.copyOf(callerScopes);
    }

    public ModuleProperties(boolean enabled, Database datasource, Migration flyway, Scheduler scheduler, Events events,
                            String clientId, Set<String> callerScopes) {
        this(enabled, datasource, null, flyway, scheduler, events, clientId, callerScopes);
    }

    /**
     * How the module's data is isolated: {@code schema} (release-1 core default) means one database for every module with one
     * schema and one role per module; {@code database} keeps a database per module (standalone services, the spike).
     *
     * @param schema the module's schema in schema mode; empty means the module name with {@code -} replaced by {@code _}
     */
    public record Isolation(@DefaultValue("schema") Mode mode, String schema) {

        public enum Mode { SCHEMA, DATABASE }
    }

    /** @param url empty means the module has no database of its own (mcp-server) */
    public record Database(String url, String username, String password,
                           @DefaultValue("4") int maximumPoolSize,
                           @DefaultValue("0") int minimumIdle,
                           @DefaultValue("10s") Duration connectionTimeout,
                           @DefaultValue("10m") Duration idleTimeout) {
    }

    /** The schema this module owns in schema mode (identifier-safe, validated). */
    public String schemaName(String moduleName) {
        String name = database.schema() == null || database.schema().isBlank()
                ? moduleName.replace('-', '_') : database.schema();
        if (!name.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Module '" + moduleName + "': schema name '" + name
                    + "' is not a lower-case identifier");
        }
        return name;
    }

    public boolean schemaMode() {
        return database.mode() == Isolation.Mode.SCHEMA;
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
