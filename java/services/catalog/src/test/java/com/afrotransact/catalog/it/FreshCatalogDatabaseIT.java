package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.afrotransact.catalog.application.CategoryService;
import com.afrotransact.catalog.domain.CategoryLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** A database no one has touched: the service migrates it from V1 to the latest version, V20 included, and starts. */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
class FreshCatalogDatabaseIT {

    private static final String URL = LegacyCatalogDatabase.createEmpty("fresh_catalog");

    private final CategoryService categories;
    private final JdbcTemplate jdbc;

    @Autowired
    FreshCatalogDatabaseIT(CategoryService categories, JdbcTemplate jdbc) {
        this.categories = categories;
        this.jdbc = jdbc;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", LegacyCatalogDatabase::user);
        registry.add("spring.datasource.password", LegacyCatalogDatabase::password);
    }

    @Test
    void theWholeTreeIsLoadedAndNothingOfTheInitialTreeIsLeft() {
        assertThat(categories.tree().stream().filter(n -> n.level() == CategoryLevel.DEPARTMENT)).hasSize(13);
        assertThat(categories.tree().stream().filter(n -> n.level() == CategoryLevel.CATEGORY)).hasSize(74);
        assertThat(categories.tree(true)).hasSameSizeAs(categories.tree());
        assertThat(jdbc.queryForObject("select count(*) from category where code like '%-legacy'", Integer.class)).isZero();
    }
}
