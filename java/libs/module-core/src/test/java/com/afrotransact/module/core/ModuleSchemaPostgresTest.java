package com.afrotransact.module.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;

/**
 * Real Postgres, only when {@code AT_TEST_PG_ADMIN_URL} (+ {@code AT_TEST_PG_ADMIN_USER}, {@code AT_TEST_PG_ADMIN_PASSWORD}) names
 * a throw-away database: two modules in one database, each with its own role, schema and Flyway history.
 */
@EnabledIfEnvironmentVariable(named = "AT_TEST_PG_ADMIN_URL", matches = ".+")
class ModuleSchemaPostgresTest {

    @Test
    void eachModuleMigratesIntoItsOwnSchemaAndCannotReadTheOther() throws SQLException {
        String url = System.getenv("AT_TEST_PG_ADMIN_URL");
        MockEnvironment env = new MockEnvironment()
                .withProperty("at.modules.schema-admin.url", url)
                .withProperty("at.modules.schema-admin.username", System.getenv("AT_TEST_PG_ADMIN_USER"))
                .withProperty("at.modules.schema-admin.password", System.getenv("AT_TEST_PG_ADMIN_PASSWORD"));
        // Two passes: the second is idempotent (nothing to migrate) and sees the other module's schema already present.
        List<String> passes = List.of("alpha", "beta", "alpha", "beta");
        for (int pass = 0; pass < passes.size(); pass++) {
            String name = passes.get(pass);
            boolean otherExists = pass >= 2;
            ModuleDefinition module = new ModuleDefinition(name, name, "x." + name, "x.App", List.of());
            ModuleProperties properties = new ModuleProperties(true, new ModuleProperties.Database(url, "mod_" + name,
                    "pw_" + name, 2, 0, Duration.ofSeconds(10), Duration.ofMinutes(1)),
                    new ModuleProperties.Migration(true, List.of("classpath:modules/" + name + "/db/migration")),
                    new ModuleProperties.Scheduler(1), new ModuleProperties.Events(false, 1, false), null, null);
            ModuleSchemaProvisioner.provisionIfConfigured(module, properties, env);
            try (HikariDataSource ds = ModuleDataSources.create(module, properties)) {
                ModuleStatus status = ModuleMigration.run(module, properties, ds, getClass().getClassLoader());
                assertThat(status.schemaVersion()).isEqualTo("1");
                try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                    ResultSet rs = s.executeQuery("select current_schema()");
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo(name);
                    s.execute("insert into widget(id) values (1) on conflict do nothing");
                    rs = s.executeQuery("select count(*) from " + name + ".flyway_schema_history");
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                    String other = name.equals("alpha") ? "beta" : "alpha";
                    if (otherExists) {
                    assertThatThrownBy(() -> s.executeQuery("select * from " + other + ".widget"))
                            .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
                    }
                }
            }
        }
    }
}
