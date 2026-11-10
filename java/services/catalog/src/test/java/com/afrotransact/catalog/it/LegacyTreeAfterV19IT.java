package com.afrotransact.catalog.it;

import com.afrotransact.catalog.application.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Upgrade of an existing catalogue database that holds data on the initial tree: V19 already recorded, V20 pending: the owner's local database, where V20 failed on category_parent_id_fkey. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "at.consumers.enabled=false", "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
class LegacyTreeAfterV19IT extends AbstractLegacyUpgradeTest {

    private static final String URL = LegacyCatalogDatabase.create("legacy_upgrade_v19", "19");

    @Autowired
    LegacyTreeAfterV19IT(CategoryService categories, JdbcTemplate jdbc, @LocalServerPort int port) {
        super(categories, jdbc, port);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", LegacyCatalogDatabase::user);
        registry.add("spring.datasource.password", LegacyCatalogDatabase::password);
    }
}
