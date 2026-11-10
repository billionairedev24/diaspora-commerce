package com.afrotransact.catalog.it;

import com.afrotransact.catalog.application.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Upgrade of an existing catalogue database that holds data on the initial tree: nothing past V18: V19 and V20 both run now. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "at.consumers.enabled=false", "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
class LegacyTreeUpgradeIT extends AbstractLegacyUpgradeTest {

    private static final String URL = LegacyCatalogDatabase.create("legacy_upgrade_v18", "18");

    @Autowired
    LegacyTreeUpgradeIT(CategoryService categories, JdbcTemplate jdbc, @LocalServerPort int port) {
        super(categories, jdbc, port);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", LegacyCatalogDatabase::user);
        registry.add("spring.datasource.password", LegacyCatalogDatabase::password);
    }
}
