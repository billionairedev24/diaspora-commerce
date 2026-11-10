package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.application.CategoryService;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What must hold after the service started on a database that already holds products, a draft, a size chart, brand
 * scopes and a category request on the initial tree of V2. The subclasses differ in how far that database got before:
 * nothing past V18, or V19 already recorded (the owner's local database, where V20 failed on a foreign key).
 */
abstract class AbstractLegacyUpgradeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final CategoryService categories;
    private final JdbcTemplate jdbc;
    private final int port;

    AbstractLegacyUpgradeTest(CategoryService categories, JdbcTemplate jdbc, int port) {
        this.categories = categories;
        this.jdbc = jdbc;
        this.port = port;
    }

    @Test
    void theNewTreeIsCompleteAndEveryNodeHasItsParent() {
        List<CategoryNode> active = categories.tree();
        assertThat(active.stream().filter(n -> n.level() == CategoryLevel.DEPARTMENT)).hasSize(13);
        assertThat(active.stream().filter(n -> n.level() == CategoryLevel.CATEGORY)).hasSize(74);
        Integer orphans = jdbc.queryForObject("""
                select count(*) from category c
                 where c.parent_id is not null and not exists (select 1 from category p where p.id = c.parent_id)
                """, Integer.class);
        assertThat(orphans).isZero();
        // The Health & Wellness department is the planned node (its id is the uuid of its code), under no parent, and its
        // categories hang from it, although a retired category of the initial tree used to have the same code.
        UUID health = TaxonomyPlanner.idOf("health-wellness");
        assertThat(categories.tree()).anySatisfy(n -> {
            assertThat(n.id()).isEqualTo(health);
            assertThat(n.level()).isEqualTo(CategoryLevel.DEPARTMENT);
            assertThat(n.active()).isTrue();
        });
        Integer children = jdbc.queryForObject("select count(*) from category where parent_id = ?", Integer.class, health);
        assertThat(children).isGreaterThan(0);
    }

    @Test
    void initialNodesThatProductsUseAreKeptRetiredAndNeverCollideWithTheNewTree() {
        assertThat(categories.tree()).noneMatch(n -> n.id().toString().equals(LegacyCatalogDatabase.HERBAL_TYPE));
        List<CategoryNode> all = categories.tree(true);
        CategoryNode herbal = all.stream().filter(n -> n.id().toString().equals(LegacyCatalogDatabase.HERBAL_TYPE))
                .findFirst().orElseThrow();
        assertThat(herbal.active()).isFalse();
        assertThat(herbal.retiredAt()).isNotNull();
        assertThat(herbal.code()).endsWith("-legacy");
        // The path stays what the product page showed.
        assertThat(herbal.path()).isEqualTo("Health › Wellness › Herbal teas & remedies");
        CategoryNode wellness = all.stream().filter(n -> n.id().toString().equals(LegacyCatalogDatabase.WELLNESS_CATEGORY))
                .findFirst().orElseThrow();
        assertThat(wellness.active()).isFalse();
        assertThat(wellness.code()).isEqualTo("health-wellness-legacy");
        // The initial nodes nothing uses are gone.
        assertThat(all).noneMatch(n -> n.id().toString().equals(LegacyCatalogDatabase.SNACKS_TYPE));
        // Every retained initial node is retired, none is reachable through the active tree.
        Integer stillActive = jdbc.queryForObject("""
                select count(*) from category where code like '%-legacy' and active
                """, Integer.class);
        assertThat(stillActive).isZero();
        // The same node id never appears twice under codes, and codes stay unique.
        Integer duplicates = jdbc.queryForObject(
                "select count(*) - count(distinct code) from category", Integer.class);
        assertThat(duplicates).isZero();
    }

    @Test
    void oldProductsStillResolveThroughThePublicApi() throws IOException, InterruptedException {
        for (String atin : List.of(LegacyCatalogDatabase.HERBAL_ATIN, LegacyCatalogDatabase.DRESS_ATIN)) {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/v1/public/products/" + atin)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(atin).isEqualTo(200);
            JsonNode product = JSON.readTree(response.body());
            String categoryId = product.get("categoryId").asText();
            assertThat(categories.tree(true)).as("the category of %s resolves", atin)
                    .anyMatch(n -> n.id().toString().equals(categoryId) && !n.active() && !n.path().isBlank());
        }
        HttpResponse<String> retiredTree = HTTP.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/v1/categories?includeRetired=true")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(retiredTree.statusCode()).isEqualTo(200);
        assertThat(retiredTree.body()).contains(LegacyCatalogDatabase.HERBAL_TYPE);
    }

    @Test
    void draftsSizeChartsRequestsAndBrandScopesSurvive() {
        assertThat(jdbc.queryForObject("select category_path from product_draft", String.class))
                .isEqualTo("Fashion › Women › Dresses");
        Map<String, Object> chart = jdbc.queryForMap("select category_id, product_type_code from size_chart");
        assertThat(chart.get("category_id").toString()).isEqualTo(LegacyCatalogDatabase.KIDS_TYPE);
        assertThat(chart.get("product_type_code")).isEqualTo("kids");
        assertThat(jdbc.queryForObject("select resolved_category_id from category_request", UUID.class).toString())
                .isEqualTo(LegacyCatalogDatabase.FICTION_TYPE);
        // The brand scoped to Women and to the Groceries department keeps where it applies in the new tree.
        List<String> scopes = jdbc.queryForList("""
                select c.code from brand_scope s join category c on c.id = s.node_id where s.brand_id = ?::uuid
                """, String.class, LegacyCatalogDatabase.LEGACY_BRAND);
        assertThat(scopes).containsExactlyInAnyOrder("adult-clothing-footwear", "groceries-food");
        assertThat(jdbc.queryForObject("""
                select count(*) from brand_scope s join category c on c.id = s.node_id where c.active = false
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select to_regclass('legacy_brand_scope')::text", String.class)).isNull();
    }
}
