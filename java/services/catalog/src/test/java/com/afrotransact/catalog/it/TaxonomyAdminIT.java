package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.catalog.application.CategoryService;
import com.afrotransact.catalog.application.CreateProductCommand;
import com.afrotransact.catalog.application.ProductService;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.CategoryRequest;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S291 staff taxonomy administration against PostgreSQL, through the HTTP API: permissions, reason + audit + event on
 * every change, optimistic locking, soft retire (refused while products use the node), moves and renames that carry
 * paths, attribute editing, and approving a category request that creates its node and product type in one step.
 * {@code AT_IT_POSTGRES_URL=jdbc:postgresql://…} runs the tests on that PostgreSQL instead of a container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "at.consumers.enabled=false", "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
@Import(TaxonomyAdminIT.Fakes.class)
class TaxonomyAdminIT {

    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = EXTERNAL_DB == null ? PostgresTestContainer.create() : null;

    static {
        if (POSTGRES != null) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_DB != null ? EXTERNAL_DB : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "") : POSTGRES.getPassword());
    }

    private static final ProductImage IMAGE = new ProductImage("https://utfs.io/f/ankara.jpg", 1600, 1600, 300_000,
            "image/jpeg");
    private static final Actor ADMIN = new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("*"));
    private static final Actor CATALOG_MANAGER = new Actor("stf-catalog", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.catalog_manager"), Set.of("catalog:taxonomy", "catalog:moderate", "catalog:write", "catalog:retail"));
    private static final AtomicReference<Actor> CURRENT = new AtomicReference<>(ADMIN);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** A switchable actor for HTTP and direct calls. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {

        @Bean
        ActorResolver switchableActorResolver() {
            return CURRENT::get;
        }
    }

    private final ProductService products;
    private final CategoryService categories;
    private final JdbcClient jdbc;
    private final int port;

    @Autowired
    TaxonomyAdminIT(ProductService products, CategoryService categories, JdbcClient jdbc, @LocalServerPort int port) {
        this.products = products;
        this.categories = categories;
        this.jdbc = jdbc;
        this.port = port;
    }

    @AfterEach
    void reset() {
        CURRENT.set(ADMIN);
    }

    // ------------------------------------------------------------------ permissions

    @Test
    void onlyTaxonomyStaffMayUseTheAdminApi() throws Exception {
        CURRENT.set(new Actor("stf-mod", PrincipalType.STAFF, Optional.empty(), Set.of("platform.moderator"),
                Set.of("catalog:moderate")));
        assertThat(call("GET", "/v1/taxonomy/tree", null, null).status()).isEqualTo(403);
        assertThat(call("POST", "/v1/taxonomy/nodes", Map.of("level", "DEPARTMENT", "name", "Nope",
                "reason", "testing"), null).status()).isEqualTo(403);
        assertThat(call("GET", "/v1/category-requests", null, null).status()).isEqualTo(403);

        CURRENT.set(new Actor("m-1", PrincipalType.SELLER_MEMBER, Optional.of("org_x"), Set.of("seller.owner"),
                Set.of("catalog:write")));
        assertThat(call("GET", "/v1/taxonomy/product-types", null, null).status()).isEqualTo(403);

        CURRENT.set(CATALOG_MANAGER);
        Reply tree = call("GET", "/v1/taxonomy/tree", null, null);
        assertThat(tree.status()).isEqualTo(200);
        assertThat(tree.body().get("nodes").size()).isGreaterThan(40);
    }

    // ------------------------------------------------------------------ create, audit, events

    @Test
    void createsTheThreeLevelsWithAReasonAnAuditRowAndAnEvent() throws Exception {
        CURRENT.set(CATALOG_MANAGER);
        String suffix = suffix();
        Reply dept = call("POST", "/v1/taxonomy/nodes", Map.of("level", "DEPARTMENT", "name", "Toys " + suffix,
                "reason", "New department for the spring launch"), null);
        assertThat(dept.status()).isEqualTo(201);
        assertThat(dept.body().get("code").asString()).isEqualTo("toys-" + suffix);
        assertThat(dept.body().get("version").asLong()).isZero();

        Reply category = call("POST", "/v1/taxonomy/nodes", Map.of("level", "CATEGORY",
                "parentId", dept.body().get("id").asString(), "name", "Board games",
                "reason", "Games are a distinct shelf"), null);
        assertThat(category.status()).isEqualTo(201);
        assertThat(category.body().get("path").asString()).isEqualTo("Toys " + suffix + " › Board games");

        Reply leaf = call("POST", "/v1/taxonomy/nodes", Map.of("level", "PRODUCT_TYPE",
                "parentId", category.body().get("id").asString(), "name", "Strategy games",
                "reason", "Strategy games sell on their own",
                "newProductType", Map.of("label", "Strategy games " + suffix, "identifierRule", "GTIN",
                        "traits", Map.of("food", false),
                        "attributes", List.of(attribute("players", "Players", "TEXT", true),
                                attribute("age", "Age", "ENUM", false, "3+", "8+", "12+")))), null);
        assertThat(leaf.status()).as(String.valueOf(leaf.body())).isEqualTo(201);
        assertThat(leaf.body().get("productType").asString()).isEqualTo("strategy_games_" + suffix);
        assertThat(leaf.body().get("productTypeLabel").asString()).isEqualTo("Strategy games " + suffix);

        // sellers see it through the unchanged public API
        assertThat(categories.tree()).anyMatch(n -> n.path().equals("Toys " + suffix + " › Board games › Strategy games"));
        Reply type = call("GET", "/v1/product-types/strategy_games_" + suffix, null, null);
        assertThat(type.body().get("attributes")).hasSize(2);
        assertThat(type.body().get("themes").get(0).get("name").asString()).isEqualTo("Option");

        // every change has an audit row with who and why, and a taxonomy event
        Reply audit = call("GET", "/v1/taxonomy/audit?subject=" + leaf.body().get("id").asString(), null, null);
        assertThat(audit.body()).hasSize(1);
        assertThat(audit.body().get(0).get("action").asString()).isEqualTo("NODE_CREATED");
        assertThat(audit.body().get(0).get("actorId").asString()).isEqualTo("stf-catalog");
        assertThat(audit.body().get(0).get("reason").asString()).isEqualTo("Strategy games sell on their own");
        assertThat(call("GET", "/v1/taxonomy/audit?subject=strategy_games_" + suffix, null, null).body().get(0).get("action")
                .asString()).isEqualTo("TYPE_CREATED");
        assertThat(events(leaf.body().get("id").asString())).isEqualTo(1);
        assertThat(events("strategy_games_" + suffix)).isEqualTo(1);

        // validation names the field: no reason, duplicate name, wrong parent level, duplicate code
        Reply noReason = call("POST", "/v1/taxonomy/nodes", Map.of("level", "DEPARTMENT", "name", "Pets " + suffix,
                "reason", "  "), null);
        assertThat(noReason.status()).isEqualTo(400);
        Reply shortReason = call("POST", "/v1/taxonomy/nodes", Map.of("level", "DEPARTMENT", "name", "Pets " + suffix,
                "reason", "ab"), null);
        assertThat(shortReason.status()).isEqualTo(422);
        assertThat(shortReason.body().get("fieldErrors").has("reason")).isTrue();
        Reply duplicate = call("POST", "/v1/taxonomy/nodes", Map.of("level", "CATEGORY",
                "parentId", dept.body().get("id").asString(), "name", "board GAMES", "reason", "again please"), null);
        assertThat(duplicate.status()).isEqualTo(422);
        assertThat(duplicate.body().get("fieldErrors").has("name")).isTrue();
        Reply wrongLevel = call("POST", "/v1/taxonomy/nodes", Map.of("level", "PRODUCT_TYPE",
                "parentId", dept.body().get("id").asString(), "name", "Misplaced", "reason", "wrong level",
                "productType", "dress"), null);
        assertThat(wrongLevel.status()).isEqualTo(422);
        assertThat(wrongLevel.body().get("fieldErrors").has("parentId")).isTrue();
        Reply dupCode = call("POST", "/v1/taxonomy/nodes", Map.of("level", "DEPARTMENT", "name", "Other " + suffix,
                "code", "toys-" + suffix, "reason", "same code"), null);
        assertThat(dupCode.status()).isEqualTo(422);
        assertThat(dupCode.body().get("fieldErrors").has("code")).isTrue();
        Reply noType = call("POST", "/v1/taxonomy/nodes", Map.of("level", "PRODUCT_TYPE",
                "parentId", category.body().get("id").asString(), "name", "Typeless", "reason", "no schema"), null);
        assertThat(noType.status()).isEqualTo(422);
        assertThat(noType.body().get("fieldErrors").has("productType")).isTrue();
    }

    // ------------------------------------------------------------------ rename, move, versions

    @Test
    void renameCarriesPathsDownAndHonoursVersions() throws Exception {
        Fixture f = fixture();
        Reply renamed = call("PATCH", "/v1/taxonomy/nodes/" + f.category, Map.of("name", "Tabletop games",
                "reason", "Better wording"), "\"0\"");
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.body().get("version").asLong()).isEqualTo(1);
        assertThat(node(f.leaf).path()).isEqualTo(f.dept + " › Tabletop games › Strategy");
        assertThat(node(f.category).path()).isEqualTo(f.dept + " › Tabletop games");

        Reply stale = call("PATCH", "/v1/taxonomy/nodes/" + f.category, Map.of("name", "Another", "reason", "too late"),
                "\"0\"");
        assertThat(stale.status()).isEqualTo(412);
        assertThat(stale.body().get("code").asString()).isEqualTo("catalog.version-mismatch");
        Reply missing = call("PATCH", "/v1/taxonomy/nodes/" + f.category, Map.of("name", "Another",
                "reason", "no version"), null);
        assertThat(missing.status()).isEqualTo(428);
        Reply unchanged = call("PATCH", "/v1/taxonomy/nodes/" + f.category, Map.of("name", "tabletop GAMES",
                "reason", "same again"), "\"1\"");
        assertThat(unchanged.status()).isEqualTo(200);
        Reply same = call("PATCH", "/v1/taxonomy/nodes/" + f.category, Map.of("name", "tabletop GAMES",
                "reason", "same again"), "\"2\"");
        assertThat(same.status()).isEqualTo(422);
        assertThat(same.body().get("fieldErrors").has("name")).isTrue();
    }

    @Test
    void movesAProductTypeBetweenCategoriesAndRefusesNonsense() throws Exception {
        Fixture f = fixture();
        Reply other = call("POST", "/v1/taxonomy/nodes", Map.of("level", "CATEGORY", "parentId", f.deptId,
                "name", "Puzzles", "reason", "Second shelf"), null);
        String puzzles = other.body().get("id").asString();
        Reply moved = call("POST", "/v1/taxonomy/nodes/" + f.leaf + "/move", Map.of("parentId", puzzles,
                "reason", "Strategy fits puzzles better"), "\"0\"");
        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.body().get("path").asString()).isEqualTo(f.dept + " › Puzzles › Strategy");
        assertThat(moved.body().get("version").asLong()).isEqualTo(1);

        assertThat(call("POST", "/v1/taxonomy/nodes/" + f.leaf + "/move", Map.of("parentId", puzzles,
                "reason", "already there"), "\"1\"").status()).isEqualTo(422);
        // a product type cannot go straight under a department, and a department cannot move
        assertThat(call("POST", "/v1/taxonomy/nodes/" + f.leaf + "/move", Map.of("parentId", f.deptId,
                "reason", "wrong level"), "\"1\"").status()).isEqualTo(422);
        assertThat(call("POST", "/v1/taxonomy/nodes/" + f.deptId + "/move", Map.of("parentId", puzzles,
                "reason", "departments stay"), "\"0\"").status()).isEqualTo(422);
        // a name already used in the target category is refused
        Reply clash = call("POST", "/v1/taxonomy/nodes", Map.of("level", "PRODUCT_TYPE", "parentId", f.category,
                "name", "Strategy", "reason", "same name elsewhere", "productType", "home"), null);
        assertThat(clash.status()).isEqualTo(201);
        Reply refused = call("POST", "/v1/taxonomy/nodes/" + clash.body().get("id").asString() + "/move",
                Map.of("parentId", puzzles, "reason", "would collide"), "\"0\"");
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.body().get("fieldErrors").has("name")).isTrue();
    }

    // ------------------------------------------------------------------ retire

    @Test
    void retireIsSoftAndRefusedWhileProductsUseTheNode() throws Exception {
        Fixture f = fixture();
        Product product = products.create(new CreateProductCommand(UUID.fromString(f.leaf), null,
                "Chess set " + suffix(), Map.of("players", "2"), List.of(IMAGE), "Option",
                List.of(new VariantDraft(Map.of("Option", "Standard"), "CHS-" + suffix(), null, true, 2500, 4)), "USD"));
        assertThat(product.categoryId()).hasToString(f.leaf);

        Reply refused = call("POST", "/v1/taxonomy/nodes/" + f.category + "/retire", Map.of("reason", "Dropping toys"),
                "\"0\"");
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.body().get("code").asString()).isEqualTo("catalog.taxonomy-in-use");
        assertThat(refused.body().get("detail").asString()).contains("1 product");
        assertThat(call("POST", "/v1/taxonomy/nodes/" + f.leaf + "/retire", Map.of("reason", "Dropping toys"),
                "\"0\"").status()).isEqualTo(409);
        assertThat(node(f.leaf).active()).isTrue();
        assertThat(call("GET", "/v1/taxonomy/tree", null, null).body().get("nodes").toString())
                .contains("\"subtreeProductCount\":1");

        // a rejected product no longer holds the category
        products.reject(product.id(), "test");
        assertThat(products.get(product.id()).status()).isEqualTo(ProductStatus.REJECTED);
        Reply retired = call("POST", "/v1/taxonomy/nodes/" + f.category + "/retire", Map.of("reason", "Dropping toys"),
                "\"0\"");
        assertThat(retired.status()).isEqualTo(200);
        assertThat(retired.body().get("active").asBoolean()).isFalse();
        assertThat(node(f.leaf).active()).isFalse();

        // gone from the sellers' tree, still resolvable for existing products, cannot take new ones
        assertThat(categories.tree()).noneMatch(n -> n.id().toString().equals(f.leaf));
        assertThat(categories.tree(true)).anyMatch(n -> n.id().toString().equals(f.leaf));
        assertThat(call("GET", "/v1/categories", null, null).body().toString()).doesNotContain(f.leaf);
        assertThat(call("GET", "/v1/categories?includeRetired=true", null, null).body().toString()).contains(f.leaf);
        assertThatThrownBy(() -> products.create(new CreateProductCommand(
                UUID.fromString(f.leaf), null, "Backgammon " + suffix(), Map.of("players", "2"), List.of(IMAGE),
                "Option", List.of(new VariantDraft(Map.of("Option", "Standard"), "BGM-" + suffix(), null, true, 2500,
                        4)), "USD"))).hasMessageContaining("Category not found");

        Reply restored = call("POST", "/v1/taxonomy/nodes/" + f.category + "/restore", Map.of("reason", "Changed our minds"),
                "\"1\"");
        assertThat(restored.status()).isEqualTo(200);
        assertThat(node(f.leaf).active()).isTrue();
        assertThat(call("GET", "/v1/taxonomy/audit?subject=" + f.category, null, null).body().findValuesAsString("action"))
                .contains("NODE_RETIRED", "NODE_RESTORED", "NODE_CREATED");
    }

    // ------------------------------------------------------------------ reorder

    @Test
    void reorderPersistsTheOrderAndRefusesAStaleList() throws Exception {
        Fixture f = fixture();
        String a = call("POST", "/v1/taxonomy/nodes", Map.of("level", "CATEGORY", "parentId", f.deptId, "name", "Alpha",
                "reason", "shelf a"), null).body().get("id").asString();
        String b = call("POST", "/v1/taxonomy/nodes", Map.of("level", "CATEGORY", "parentId", f.deptId, "name", "Beta",
                "reason", "shelf b"), null).body().get("id").asString();
        List<String> before = List.of(f.category, a, b);
        Reply reordered = call("POST", "/v1/taxonomy/reorder", Map.of("parentId", f.deptId,
                "ids", List.of(b, f.category, a), "reason", "Beta first"), null);
        assertThat(reordered.status()).isEqualTo(200);
        assertThat(reordered.body().findValuesAsString("id")).containsExactly(b, f.category, a);
        assertThat(categories.tree(true).stream().filter(n -> f.deptId.equals(String.valueOf(n.parentId())))
                .map(n -> n.id().toString()).toList()).containsExactly(b, f.category, a);
        assertThat(call("POST", "/v1/taxonomy/reorder", Map.of("parentId", f.deptId, "ids", List.of(b, f.category, a),
                "reason", "nothing changes"), null).status()).isEqualTo(422);
        assertThat(call("POST", "/v1/taxonomy/reorder", Map.of("parentId", f.deptId, "ids", List.of(b, a),
                "reason", "forgot one"), null).status()).isEqualTo(412);
        assertThat(before).hasSize(3);
    }

    // ------------------------------------------------------------------ attributes

    @Test
    void editsAttributesAndAnAttributeThatDoesNotApplyStopsBeingAsked() throws Exception {
        Fixture f = fixture();
        Reply type = call("GET", "/v1/taxonomy/product-types/" + f.type, null, null);
        assertThat(type.body().get("version").asLong()).isZero();
        assertThat(type.body().get("usedBy").get(0).get("path").asString()).endsWith("Strategy");

        List<Map<String, Object>> next = new ArrayList<>(List.of(
                attribute("brand", "Brand", "TEXT", true),
                attribute("players", "Players", "TEXT", true),
                attribute("weight", "Weight", "NUMBER", false, List.of("g", "kg"))));
        Reply edited = call("PATCH", "/v1/taxonomy/product-types/" + f.type, Map.of("attributes", next,
                "reason", "Add brand and weight"), "\"0\"");
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.body().get("version").asLong()).isEqualTo(1);
        assertThat(edited.body().get("attributes")).hasSize(3);
        assertThat(call("GET", "/v1/product-types/" + f.type, null, null).body().get("attributes")).hasSize(3);

        // brand is only an attribute: switch it off for this type
        next.set(0, attribute("brand", "Brand", "TEXT", false, null, false));
        Reply off = call("PATCH", "/v1/taxonomy/product-types/" + f.type, Map.of("attributes", next,
                "reason", "No brand on board games"), "\"1\"");
        assertThat(off.status()).isEqualTo(200);
        assertThat(off.body().get("attributes")).hasSize(3);
        Reply sellerView = call("GET", "/v1/product-types/" + f.type, null, null);
        assertThat(sellerView.body().get("attributes").findValuesAsString("key")).containsExactly("players", "weight");
        // and a product without one is accepted
        Product ok = products.create(new CreateProductCommand(UUID.fromString(f.leaf), null, "Catan " + suffix(),
                Map.of("players", "3-4"), List.of(IMAGE), "Option",
                List.of(new VariantDraft(Map.of("Option", "Standard"), "CTN-" + suffix(), null, true, 4500, 4)), "USD"));
        assertThat(ok.status()).isEqualTo(ProductStatus.IN_REVIEW);

        // nothing changed, stale version, invalid definitions
        assertThat(call("PATCH", "/v1/taxonomy/product-types/" + f.type, Map.of("attributes", next, "reason", "same"),
                "\"2\"").status()).isEqualTo(422);
        assertThat(call("PATCH", "/v1/taxonomy/product-types/" + f.type, Map.of("label", "Strategy games!",
                "reason", "late"), "\"0\"").status()).isEqualTo(412);
        Reply invalid = call("PATCH", "/v1/taxonomy/product-types/" + f.type, Map.of("attributes", List.of(
                attribute("Bad Key", "Oops", "TEXT", false), attribute("players", "Players", "ENUM", false)),
                "reason", "invalid"), "\"2\"");
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.body().get("fieldErrors").has("attributes[0].key")).isTrue();
        assertThat(invalid.body().get("fieldErrors").has("attributes[1].options")).isTrue();
        Reply foodSection = call("PATCH", "/v1/taxonomy/product-types/" + f.type, Map.of("attributes", List.of(
                attribute("allergens", "Allergens", "TEXT", false, null, true, "FOOD")), "reason", "needs food trait"),
                "\"2\"");
        assertThat(foodSection.status()).isEqualTo(422);
        // the placeholder type and the sales-tax question are protected
        assertThat(call("PATCH", "/v1/taxonomy/product-types/custom", Map.of("label", "Mine", "reason", "tamper"),
                "\"0\"").status()).isEqualTo(422);
        JsonNode grocery = call("GET", "/v1/taxonomy/product-types/grocery", null, null).body();
        List<Map<String, Object>> groceryAttributes = new ArrayList<>();
        for (JsonNode a : grocery.get("attributes")) {
            if (!"tax_class".equals(a.get("key").asString())) {
                groceryAttributes.add(JSON.convertValue(a, Map.class));
            }
        }
        Reply taxRemoved = call("PATCH", "/v1/taxonomy/product-types/grocery", Map.of("attributes", groceryAttributes,
                "reason", "drop the tax question"), "\"" + grocery.get("version").asLong() + "\"");
        assertThat(taxRemoved.status()).isEqualTo(422);
        assertThat(taxRemoved.body().get("fieldErrors").get("attributes").asString()).contains("tax_class");

        Reply audit = call("GET", "/v1/taxonomy/audit?subject=" + f.type, null, null);
        assertThat(audit.body().findValuesAsString("action")).contains("TYPE_UPDATED", "TYPE_CREATED");
        assertThat(audit.body().toString()).contains("\"added\":[\"brand\",\"weight\"]").contains("\"changed\":[\"brand\"]");
    }

    @Test
    void identifierRuleAndTraitsAreEditable() throws Exception {
        Fixture f = fixture();
        Reply edited = call("PATCH", "/v1/taxonomy/product-types/" + f.type, Map.of("identifierRule", "ISBN",
                "traits", Map.of("food", true, "perishable", true, "regulated", false, "preorder", true),
                "reason", "Treat as chilled food"), "\"0\"");
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.body().get("identifierRule").asString()).isEqualTo("ISBN");
        assertThat(edited.body().get("traits").get("perishable").asBoolean()).isTrue();
        Reply bad = call("PATCH", "/v1/taxonomy/product-types/" + f.type, Map.of("traits", Map.of("food", false,
                "perishable", true, "regulated", false, "preorder", false), "reason", "inconsistent"), "\"1\"");
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body().get("fieldErrors").has("traits")).isTrue();
    }

    // ------------------------------------------------------------------ category requests

    @Test
    void approvingARequestCreatesItsNodeAndProductTypeInOneStep() throws Exception {
        CURRENT.set(CATALOG_MANAGER);
        String name = "Bridal sets " + suffix();
        CategoryRequest request = categories.request("Fashion › Women", name, "6-piece aso-ebi set",
                "Pieces, colour, fabric");
        Product waiting = products.create(new CreateProductCommand(null, request.id(), "Aso-ebi bridal " + suffix(),
                Map.of("brand", "Ife Bridal", "origin", "Nigeria", "material", "Aso-oke", "details", "Gele, ipele, iro"), List.of(IMAGE), "Option",
                List.of(new VariantDraft(Map.of("Option", "Standard"), "ASO-" + suffix(), null, true, 25000, 2)), "USD"));
        assertThat(waiting.status()).isEqualTo(ProductStatus.PENDING_CATEGORY_APPROVAL);

        Reply queue = call("GET", "/v1/category-requests?status=PENDING_REVIEW", null, null);
        JsonNode mine = find(queue.body(), "id", request.id().toString());
        assertThat(mine.get("productsWaiting").asLong()).isEqualTo(1);
        assertThat(mine.get("parentPath").asString()).isEqualTo("Fashion › Women");
        assertThat(mine.get("name").asString()).isEqualTo(name);

        Reply detail = call("GET", "/v1/category-requests/" + request.id(), null, null);
        assertThat(detail.body().get("products")).hasSize(1);
        String women = detail.body().get("suggestedParent").get("id").asString();
        assertThat(detail.body().get("suggestedParent").get("path").asString()).isEqualTo("Fashion › Women");

        // the preview says what will be created and creates nothing
        long nodesBefore = categories.tree(true).size();
        Map<String, Object> body = Map.of("parentCategoryId", women, "note", "Launching bridal",
                "newProductType", Map.of("label", name, "identifierRule", "GTIN",
                        "attributes", List.of(attribute("pieces", "Pieces", "TEXT", true))));
        Reply plan = call("POST", "/v1/category-requests/" + request.id() + "/approval-preview", body, null);
        assertThat(plan.status()).isEqualTo(200);
        assertThat(plan.body().get("nodes")).hasSize(1);
        assertThat(plan.body().get("nodes").get(0).get("path").asString()).isEqualTo("Fashion › Women › " + name);
        assertThat(plan.body().get("productType").get("created").asBoolean()).isTrue();
        assertThat(plan.body().get("productType").get("attributeCount").asInt()).isEqualTo(1);
        assertThat(plan.body().get("productsMoving").asInt()).isEqualTo(1);
        assertThat(categories.tree(true)).hasSize((int) nodesBefore);

        Reply bothTypes = call("POST", "/v1/category-requests/" + request.id() + "/approve",
                Map.of("parentCategoryId", women, "productType", "top", "newProductType",
                        Map.of("label", name, "identifierRule", "GTIN")), null);
        assertThat(bothTypes.status()).isEqualTo(422);

        Reply approved = call("POST", "/v1/category-requests/" + request.id() + "/approve", body, null);
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.body().get("status").asString()).isEqualTo("APPROVED");
        CategoryNode created = categories.tree().stream().filter(n -> n.path().equals("Fashion › Women › " + name))
                .findFirst().orElseThrow();
        assertThat(created.productTypeCode()).isNotNull();
        assertThat(categories.productType(created.productTypeCode()).label()).isEqualTo(name);
        Product moved = products.get(waiting.id());
        assertThat(moved.status()).isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(moved.categoryId()).isEqualTo(created.id());

        Reply audit = call("GET", "/v1/taxonomy/audit?subject=" + request.id(), null, null);
        assertThat(audit.body().get(0).get("action").asString()).isEqualTo("REQUEST_APPROVED");
        assertThat(audit.body().get(0).get("reason").asString()).contains(request.reference()).contains("Launching");
        assertThat(call("POST", "/v1/category-requests/" + request.id() + "/approve", body, null).status())
                .isEqualTo(409);
    }

    @Test
    void approvingUnderADepartmentCreatesTheCategoryToo() throws Exception {
        CURRENT.set(CATALOG_MANAGER);
        Fixture f = fixture();
        String name = "Chess sets " + suffix();
        CategoryRequest request = categories.request(f.dept, name, "Wooden chess set", "Board size, material");
        Reply plan = call("POST", "/v1/category-requests/" + request.id() + "/approval-preview",
                Map.of("parentCategoryId", f.deptId, "categoryName", "Classic games", "productType", "home"), null);
        assertThat(plan.status()).isEqualTo(200);
        assertThat(plan.body().get("nodes")).hasSize(2);
        assertThat(plan.body().get("nodes").get(0).get("level").asString()).isEqualTo("CATEGORY");
        assertThat(plan.body().get("nodes").get(0).get("created").asBoolean()).isTrue();
        assertThat(plan.body().get("productType").get("created").asBoolean()).isFalse();

        Reply approved = call("POST", "/v1/category-requests/" + request.id() + "/approve",
                Map.of("parentCategoryId", f.deptId, "categoryName", "Classic games", "productType", "home"), null);
        assertThat(approved.status()).isEqualTo(200);
        assertThat(categories.tree()).anyMatch(n -> n.path().equals(f.dept + " › Classic games"))
                .anyMatch(n -> n.path().equals(f.dept + " › Classic games › " + name));
        // a second request in the same category reuses it
        CategoryRequest second = categories.request(f.dept, "Checkers " + suffix(), "Checkers", "Board size");
        Reply reuse = call("POST", "/v1/category-requests/" + second.id() + "/approval-preview",
                Map.of("parentCategoryId", f.deptId, "categoryName", "classic GAMES", "productType", "home"), null);
        assertThat(reuse.body().get("nodes").get(0).get("created").asBoolean()).isFalse();
    }

    @Test
    void rejectingARequestIsAuditedAndNeedsAReason() throws Exception {
        CURRENT.set(CATALOG_MANAGER);
        CategoryRequest request = categories.request("Fashion › Women", "Odd things " + suffix(), "Something odd",
                "Unclear");
        assertThat(call("POST", "/v1/category-requests/" + request.id() + "/reject", Map.of("note", "no"), null)
                .status()).isEqualTo(400);
        Reply rejected = call("POST", "/v1/category-requests/" + request.id() + "/reject",
                Map.of("note", "Already covered by Tops"), null);
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(call("GET", "/v1/taxonomy/audit?subject=" + request.id(), null, null).body().get(0).get("action")
                .asString()).isEqualTo("REQUEST_REJECTED");
    }

    // ------------------------------------------------------------------ helpers

    /** A department › category › product type of the test's own, with a one-attribute schema. */
    private Fixture fixture() throws Exception {
        CURRENT.set(CATALOG_MANAGER);
        String suffix = suffix();
        String dept = "Games " + suffix;
        Reply d = call("POST", "/v1/taxonomy/nodes", Map.of("level", "DEPARTMENT", "name", dept,
                "reason", "Fixture department"), null);
        String deptId = d.body().get("id").asString();
        String category = call("POST", "/v1/taxonomy/nodes", Map.of("level", "CATEGORY", "parentId", deptId,
                "name", "Board games", "reason", "Fixture category"), null).body().get("id").asString();
        Reply leaf = call("POST", "/v1/taxonomy/nodes", Map.of("level", "PRODUCT_TYPE", "parentId", category,
                "name", "Strategy", "reason", "Fixture product type",
                "newProductType", Map.of("code", "strategy_" + suffix, "label", "Strategy " + suffix,
                        "identifierRule", "GTIN", "attributes", List.of(attribute("players", "Players", "TEXT", true)))),
                null);
        assertThat(leaf.status()).isEqualTo(201);
        return new Fixture(dept, deptId, category, leaf.body().get("id").asString(), "strategy_" + suffix);
    }

    private record Fixture(String dept, String deptId, String category, String leaf, String type) {
    }

    private CategoryNode node(String id) {
        return categories.tree(true).stream().filter(n -> n.id().toString().equals(id)).findFirst().orElseThrow();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private static Map<String, Object> attribute(String key, String label, String kind, boolean required,
                                                 String... options) {
        return attribute(key, label, kind, required, List.of(options), true);
    }

    private static Map<String, Object> attribute(String key, String label, String kind, boolean required,
                                                 List<String> unitsOrOptions) {
        return attribute(key, label, kind, required, unitsOrOptions, true);
    }

    private static Map<String, Object> attribute(String key, String label, String kind, boolean required,
                                                 List<String> unitsOrOptions, boolean applies) {
        return attribute(key, label, kind, required, unitsOrOptions, applies, "DETAILS");
    }

    private static Map<String, Object> attribute(String key, String label, String kind, boolean required,
                                                 List<String> unitsOrOptions, boolean applies, String section) {
        List<String> list = unitsOrOptions == null ? List.of() : unitsOrOptions;
        return Map.of("key", key, "label", label, "kind", kind, "required", required, "applies", applies,
                "section", section, "options", "ENUM".equals(kind) ? list : List.of(),
                "units", "NUMBER".equals(kind) ? list : List.of());
    }

    private long events(String aggregateId) {
        return jdbc.sql("select count(*) from platform_outbox where type = 'catalog.taxonomy.changed' "
                + "and aggregate_id = :id").param("id", aggregateId).query(Long.class).single();
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.get(field).asString())) {
                return n;
            }
        }
        throw new AssertionError("No element with " + field + "=" + value + " in " + array);
    }

    private Reply call(String method, String path, Object body, String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body()));
    }

    private record Reply(int status, JsonNode body) {
    }
}
