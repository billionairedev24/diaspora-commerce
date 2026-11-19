package com.afrotransact.identity.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V54: the permission that lets staff change the picture rules exists and only the intended roles hold it. */
@SpringBootTest(properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
@Testcontainers
class PictureRulesPermissionMigrationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private final JdbcClient jdbc;

    @Autowired
    PictureRulesPermissionMigrationIT(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void thePermissionIsInTheCatalogueWithAPlainDescription() {
        assertThat(jdbc.sql("select description from permission where key = 'catalog:picture-rules.manage'")
                .query(String.class).single()).contains("picture rules");
    }

    @Test
    void superAdminDeveloperAndCatalogManagerHoldItByDefault() {
        List<String> roles = jdbc.sql("select role_key from role_permission where permission_key = "
                + "'catalog:picture-rules.manage' order by role_key").query(String.class).list();
        assertThat(roles).containsExactly("platform.catalog_manager", "platform.developer", "platform.super_admin");
    }

    @Test
    void itIsNotGrantedToAnyOAuthClientScope() {
        List<String> tables = jdbc.sql("select table_name from information_schema.columns where column_name = 'scopes' "
                + "and table_schema = current_schema()").query(String.class).list();
        for (String table : tables) {
            assertThat(jdbc.sql("select count(*) from " + table + " where scopes::text like '%picture-rules.manage%'")
                    .query(Long.class).single()).as(table).isZero();
        }
    }
}
