package com.afrotransact.module.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Schema-per-module mode: defaults, switch, pinned search_path, pool bounds and the provisioning statements. */
class ModuleSchemaModeTest {

    private static ModuleProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source)).bindOrCreate("at.modules.tax-returns",
                ModuleProperties.class);
    }

    private static final ModuleDefinition MODULE = new ModuleDefinition("tax-returns", "tax-returns",
            "com.afrotransact.taxreturns", "x.App", List.of());

    @Test
    void schemaIsTheDefaultAndTheNameIsDerivedFromTheModule() {
        ModuleProperties properties = bind(Map.of());
        assertThat(properties.schemaMode()).isTrue();
        assertThat(properties.schemaName("tax-returns")).isEqualTo("tax_returns");
    }

    @Test
    void databaseModeKeepsTheSeparateDatabaseBehaviour() {
        ModuleProperties properties = bind(Map.of("at.modules.tax-returns.database.mode", "database"));
        assertThat(properties.schemaMode()).isFalse();
        HikariDataSource ds = ModuleDataSources.create(MODULE, withUrl(properties));
        assertThat(ds.getConnectionInitSql()).isNull();
        ds.close();
    }

    @Test
    void schemaModePinsTheSearchPathAndKeepsThePoolSmall() {
        ModuleProperties properties = bind(Map.of(
                "at.modules.tax-returns.datasource.url", "jdbc:postgresql://db/core",
                "at.modules.tax-returns.datasource.username", "tax_returns"));
        HikariDataSource ds = ModuleDataSources.create(MODULE, properties);
        assertThat(ds.getConnectionInitSql()).isEqualTo("SET search_path TO \"tax_returns\", public");
        assertThat(ds.getMaximumPoolSize()).isEqualTo(4);
        assertThat(ds.getMinimumIdle()).isZero();
        ds.close();
    }

    @Test
    void anExplicitSchemaOverridesAndAnUnsafeNameIsRefused() {
        assertThat(bind(Map.of("at.modules.tax-returns.database.schema", "tr")).schemaName("tax-returns"))
                .isEqualTo("tr");
        assertThatThrownBy(() -> bind(Map.of("at.modules.tax-returns.database.schema", "x\"; drop"))
                .schemaName("tax-returns")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void provisioningIsIdempotentSqlWithAnEscapedPassword() {
        assertThat(ModuleSchemaProvisioner.statements("tax_returns", "tax_returns", "p'w"))
                .anyMatch(s -> s.startsWith("CREATE SCHEMA IF NOT EXISTS \"tax_returns\" AUTHORIZATION"))
                .anyMatch(s -> s.contains("PASSWORD 'p''w'"))
                .anyMatch(s -> s.contains("SET search_path = \"tax_returns\", public"));
        assertThatThrownBy(() -> ModuleSchemaProvisioner.statements("a", "b", "")).isInstanceOf(
                IllegalArgumentException.class);
    }

    private static ModuleProperties withUrl(ModuleProperties properties) {
        return new ModuleProperties(true, new ModuleProperties.Database("jdbc:postgresql://db/x", "u", "p", 4, 0,
                Duration.ofSeconds(10), Duration.ofMinutes(10)), properties.database(),
                properties.flyway(), properties.scheduler(), properties.events(), null, null);
    }
}
