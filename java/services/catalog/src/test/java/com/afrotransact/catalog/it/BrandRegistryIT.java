package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

import com.afrotransact.catalog.application.CategoryService;
import com.afrotransact.catalog.application.CreateProductCommand;
import com.afrotransact.catalog.application.ProductService;
import com.afrotransact.catalog.application.UpdateProductCommand;
import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S326 brand registry against PostgreSQL, through the HTTP API: the brand list of a category never holds a brand of an
 * unrelated department, a typed brand is pending and visible only to its seller, names are matched ignoring case,
 * accents and punctuation, types without a brand attribute keep none, "no brand" is a reason and never the text
 * "Generic", staff decisions (approve, reject, merge, edit, bulk add, retire) with reason, audit and versions, and the
 * backfill of free-text brands. {@code AT_IT_POSTGRES_URL=jdbc:postgresql://…} runs it on that PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "at.consumers.enabled=false", "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
@Import(TestActors.class)
class BrandRegistryIT {

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
    private static final Actor STAFF = new Actor("stf-catalog", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.catalog_manager"), Set.of("catalog:taxonomy", "catalog:moderate", "catalog:write"));
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final ProductService products;
    private final CategoryService categories;
    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final int port;

    @Autowired
    BrandRegistryIT(ProductService products, CategoryService categories, JdbcClient jdbc, DataSource dataSource,
                    @LocalServerPort int port) {
        this.products = products;
        this.categories = categories;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.port = port;
    }

    @AfterEach
    void reset() {
        TestActors.reset();
    }

    // ------------------------------------------------------------------ scoping

    @Test
    void aCategorysBrandListNeverHoldsABrandOfAnUnrelatedDepartment() throws Exception {
        String sfx = suffix();
        String phone = electronicsLeaf(sfx);
        String samsung = "Samsung " + sfx;
        String dept = department(phone);
        staffPost("/v1/taxonomy/brands", Map.of("name", samsung, "scope", List.of(dept), "reason", "Phone maker"), 201);

        seller("org_scope");
        JsonNode dresses = get("/v1/brands?category=" + leaf("Fashion › Women › Dresses").id(), 200);
        assertThat(names(dresses)).contains("Nike", "Levi's").doesNotContain(samsung, "Maggi", "Cantu", "Vlisco");
        assertThat(dresses.get("departmentName").asString()).isEqualTo("Fashion");
        JsonNode byType = get("/v1/brands?productType=dress", 200);
        assertThat(names(byType)).contains("Nike").doesNotContain(samsung, "Maggi");
        assertThat(names(get("/v1/brands?category=" + phone, 200))).contains(samsung).doesNotContain("Nike", "Maggi");
        assertThat(names(get("/v1/brands?category=" + leaf("Groceries › Pantry › Snacks").id(), 200)))
                .contains("Maggi", "Indomie").doesNotContain("Nike", samsung);
        // fabrics are Fashion too, but sportswear labels are scoped to clothing categories only
        assertThat(names(get("/v1/brands?category=" + leaf("Fashion › Fabrics › Lace").id(), 200)))
                .contains("Vlisco").doesNotContain("Nike");
        // books have no brand at all: nothing is scoped there
        assertThat(get("/v1/brands?category=" + leaf("Books › Books › Fiction").id(), 200).get("brands")).isEmpty();
        // the typed query ignores case, accents and punctuation
        assertThat(names(get("/v1/brands?category=" + leaf("Fashion › Men › Shirts").id() + "&q=LEVIS", 200)))
                .containsExactly("Levi's");
        // neither category nor product type is refused
        assertThat(call("GET", "/v1/brands", null, null).status()).isEqualTo(422);
    }

    @Test
    void ranksTheSellersOwnUseBeforePopularity() throws Exception {
        seller("org_rank");
        CategoryNode shirts = leaf("Fashion › Men › Shirts");
        String sfx = suffix();
        createDress(shirts, "Zara", "RK-" + sfx);
        createDress(shirts, "Zara", "RK2-" + sfx);
        JsonNode own = get("/v1/brands?category=" + shirts.id(), 200);
        assertThat(names(own).getFirst()).isEqualTo("Zara");
        assertThat(own.get("brands").get(0).get("ownProducts").asLong()).isEqualTo(2);
        seller("org_rank_other");
        JsonNode other = get("/v1/brands?category=" + shirts.id(), 200);
        assertThat(names(other).getFirst()).isEqualTo("Adidas");
        assertThat(other.get("brands").get(0).get("ownProducts").asLong()).isZero();
    }

    // ------------------------------------------------------------------ typing a brand

    @Test
    void aTypedBrandIsPendingScopedToItsDepartmentAndSeenOnlyByItsSeller() throws Exception {
        String sfx = suffix();
        String typed = "Cotedivoire " + sfx;
        CategoryNode dresses = leaf("Fashion › Women › Dresses");
        seller("org_ci_a");
        JsonNode created = post("/v1/brands", Map.of("name", typed, "category", dresses.id().toString()), 201);
        assertThat(created.get("created").asBoolean()).isTrue();
        assertThat(created.get("brand").get("status").asString()).isEqualTo("PENDING");
        String id = created.get("brand").get("id").asString();

        // the owner sees it under Fashion, never under Groceries or Books: the Cotedivoire-under-Dresses bug
        assertThat(names(get("/v1/brands?category=" + dresses.id(), 200))).contains(typed);
        assertThat(names(get("/v1/brands?category=" + leaf("Fashion › Fabrics › Lace").id(), 200))).contains(typed);
        assertThat(names(get("/v1/brands?category=" + leaf("Groceries › Pantry › Snacks").id(), 200)))
                .doesNotContain(typed);
        assertThat(names(get("/v1/brands?category=" + leaf("Beauty › Hair care › Hair oils").id(), 200)))
                .doesNotContain(typed);
        // another seller does not see it
        seller("org_ci_b");
        assertThat(names(get("/v1/brands?category=" + dresses.id(), 200))).doesNotContain(typed);

        // typing the same name differently returns the match instead of a duplicate
        seller("org_ci_a");
        for (String again : List.of(typed.toUpperCase(), "  " + typed.toLowerCase() + " ", typed.replace(" ", "  "))) {
            JsonNode match = post("/v1/brands", Map.of("name", again, "category", dresses.id().toString()), 200);
            assertThat(match.get("created").asBoolean()).isFalse();
            assertThat(match.get("brand").get("id").asString()).isEqualTo(id);
        }

        // and it can be used straight away on a listing: the product stores the id and the display name
        Product p = createDress(dresses, typed, "CI-" + sfx);
        assertThat(p.attributes()).containsEntry("brand", typed).containsEntry("brand_id", id);
        assertThat(jdbc.sql("select status from brand where id = cast(:id as uuid)").param("id", id)
                .query(String.class).single()).isEqualTo("PENDING");
    }

    @Test
    void namesAreMatchedIgnoringCaseAccentsAndPunctuation() throws Exception {
        CategoryNode dresses = leaf("Fashion › Women › Dresses");
        CategoryNode snacks = leaf("Groceries › Pantry › Snacks");
        seller("org_norm");
        for (String variant : List.of("NIKE", "nike", "N.i.k.e", " Nike ")) {
            JsonNode r = post("/v1/brands", Map.of("name", variant, "category", dresses.id().toString()), 200);
            assertThat(r.get("created").asBoolean()).isFalse();
            assertThat(r.get("brand").get("name").asString()).isEqualTo("Nike");
            assertThat(r.get("brand").get("status").asString()).isEqualTo("APPROVED");
        }
        assertThat(post("/v1/brands", Map.of("name", "Levis", "category", dresses.id().toString()), 200)
                .get("brand").get("name").asString()).isEqualTo("Levi's");
        assertThat(post("/v1/brands", Map.of("name", "NESTLE", "category", snacks.id().toString()), 200)
                .get("brand").get("name").asString()).isEqualTo("Nestlé");
        // a typed name on a product is canonicalised too
        Product p = createDress(dresses, "NIKE", "NM-" + suffix());
        assertThat(p.attributes().get("brand")).isEqualTo("Nike");
        // a name without letters or digits is refused
        assertThat(call("POST", "/v1/brands", Map.of("name", "!!!", "category", dresses.id().toString()), null)
                .status()).isEqualTo(422);
    }

    @Test
    void aBrandChosenFromAnotherDepartmentIsRefused() throws Exception {
        seller("org_cross");
        CategoryNode dresses = leaf("Fashion › Women › Dresses");
        String maggi = jdbc.sql("select id::text from brand where name = 'Maggi'").query(String.class).single();
        assertThatThrownBy(() -> createDress(dresses, Map.of("brand", "Maggi", "brand_id", maggi), "XD-" + suffix()))
                .isInstanceOf(CatalogRuleViolation.class)
                .satisfies(e -> assertThat(((CatalogRuleViolation) e).fieldErrors()).containsKey("attributes.brand"));
    }

    // ------------------------------------------------------------------ types decide whether a brand is asked

    @Test
    void theBrandAttributeOfEachTypeComesFromItsDefinition() {
        assertThat(brand("dress")).satisfies(b -> {
            assertThat(b.label()).isEqualTo("Brand or label");
            assertThat(b.required()).isTrue();
            assertThat(b.unbrandedReasons()).extracting(Enum::name).containsExactly("UNBRANDED", "HANDMADE");
        });
        assertThat(brand("beauty")).satisfies(b -> {
            assertThat(b.required()).isTrue();
            assertThat(b.unbrandedReasons()).isEmpty();
        });
        assertThat(brand("grocery").required()).isFalse();
        assertThat(brand("home").label()).isEqualTo("Maker or artisan");
        assertThat(brand("electronics").required()).isTrue();
        assertThat(categories.productType("electronics").attribute("model").orElseThrow().required()).isTrue();
        assertThat(categories.productType("book").brandAttribute()).isEmpty();
    }

    @Test
    void aBookHasNoBrandEvenWhenOneIsSent() {
        seller("org_book");
        CategoryNode fiction = leaf("Books › Books › Fiction");
        ProductType book = categories.productType("book");
        Map<String, String> attrs = new LinkedHashMap<>(fill(book));
        attrs.put("brand", "Penguin Random House");
        attrs.put("brand_id", UUID.randomUUID().toString());
        Product p = products.create(new CreateProductCommand(fiction.id(), null, "Things Fall Apart " + suffix(), attrs,
                List.of(IMAGE), "", List.of(new VariantDraft(Map.of(), "BK-" + suffix(), isbn(), false, 1299,
                        3)), "USD"));
        assertThat(p.attributes()).doesNotContainKeys("brand", "brand_id", "brand_reason");
    }

    @Test
    void noBrandIsAReasonNeverTheTextGeneric() throws Exception {
        seller("org_generic");
        CategoryNode dresses = leaf("Fashion › Women › Dresses");
        Product viaForm = createDress(dresses, Map.of("brand_reason", "HANDMADE"), "GN-" + suffix());
        assertThat(viaForm.attributes()).containsEntry("brand_reason", "HANDMADE").doesNotContainKeys("brand", "brand_id");
        Product typed = createDress(dresses, Map.of("brand", "Generic"), "GN2-" + suffix());
        assertThat(typed.attributes()).containsEntry("brand_reason", "UNBRANDED").doesNotContainKey("brand");
        assertThat(jdbc.sql("select count(*) from brand where normalized_name = 'generic'").query(Long.class).single())
                .isZero();
        // beauty is regulated: a brand is needed, and "unbranded" is not accepted there
        CategoryNode oils = leaf("Beauty › Hair care › Hair oils");
        Map<String, String> beauty = new LinkedHashMap<>(fill(categories.productType("beauty")));
        beauty.remove("brand");
        beauty.put("brand", "No brand");
        assertThatThrownBy(() -> products.create(new CreateProductCommand(oils.id(), null, "Castor oil " + suffix(),
                beauty, List.of(IMAGE), "Size", List.of(new VariantDraft(Map.of("Size", "8 oz"), "BE-" + suffix(), null,
                        true, 1299, 3)), "USD"))).isInstanceOf(CatalogRuleViolation.class)
                .satisfies(e -> assertThat(((CatalogRuleViolation) e).fieldErrors()).containsKey("attributes.brand"));
        // a required brand with nothing chosen is refused
        assertThatThrownBy(() -> createDress(dresses, Map.of(), "GN3-" + suffix()))
                .isInstanceOf(CatalogRuleViolation.class);
    }

    @Test
    void changingTheCategoryRevalidatesTheBrand() {
        seller("org_move");
        CategoryNode dresses = leaf("Fashion › Women › Dresses");
        CategoryNode snacks = leaf("Groceries › Pantry › Snacks");
        Product p = createDress(dresses, "Nike", "MV-" + suffix());
        // moving the (not yet published) listing to groceries while it keeps the fashion brand is refused
        assertThatThrownBy(() -> products.update(p.id(), new UpdateProductCommand(snacks.id(), p.title(),
                p.attributes(), p.images(), "", List.of(new VariantDraft(Map.of(), "MV-X" + suffix(), null, true, 1299,
                        3)), null, null, p.details(), p.version(), null)))
                .isInstanceOf(CatalogRuleViolation.class)
                .satisfies(e -> assertThat(((CatalogRuleViolation) e).fieldErrors()).containsKey("attributes.brand"));
        // staying within fashion keeps it
        CategoryNode shirts = leaf("Fashion › Women › Tops");
        Product moved = products.update(p.id(), new UpdateProductCommand(shirts.id(), p.title(), p.attributes(),
                p.images(), "", List.of(new VariantDraft(Map.of(), "MV-Y" + suffix(), null, true, 1299, 3)), null, null,
                p.details(), p.version(), null));
        assertThat(moved.attributes()).containsEntry("brand", "Nike");
    }

    // ------------------------------------------------------------------ staff

    @Test
    void staffReviewApproveRejectAndAuditEveryChange() throws Exception {
        String sfx = suffix();
        CategoryNode dresses = leaf("Fashion › Women › Dresses");
        seller("org_q_a");
        String typed = "Adire Lagos " + sfx;
        createDress(dresses, typed, "Q1-" + sfx);
        createDress(dresses, typed, "Q2-" + sfx);
        asStaff();
        assertThat(call("GET", "/v1/taxonomy/brands?status=PENDING", null, null).status()).isEqualTo(200);
        JsonNode queue = get("/v1/taxonomy/brands?status=PENDING&q=" + typed.replace(" ", ""), 200);
        assertThat(queue.get("brands")).hasSize(1);
        JsonNode row = queue.get("brands").get(0);
        assertThat(row.get("products").asLong()).isEqualTo(2);
        assertThat(row.get("sellers").asLong()).isEqualTo(1);
        assertThat(row.get("createdByOrg").asString()).isEqualTo("org_q_a");
        assertThat(row.get("scope").get(0).get("path").asString()).isEqualTo("Fashion");
        assertThat(queue.get("counts").get("PENDING").asLong()).isGreaterThanOrEqualTo(1);
        String id = row.get("id").asString();

        // a reason is required, a version is required, a stale one is refused
        assertThat(call("POST", "/v1/taxonomy/brands/" + id + "/approve", Map.of("reason", "ab"), "\"0\"").status())
                .isEqualTo(422);
        assertThat(call("POST", "/v1/taxonomy/brands/" + id + "/approve", Map.of("reason", "Real label"), null)
                .status()).isEqualTo(428);
        assertThat(call("POST", "/v1/taxonomy/brands/" + id + "/approve", Map.of("reason", "Real label"), "\"7\"")
                .status()).isEqualTo(412);
        JsonNode approved = postIfMatch("/v1/taxonomy/brands/" + id + "/approve", Map.of("reason", "Real Lagos label"),
                "\"0\"", 200);
        assertThat(approved.get("status").asString()).isEqualTo("APPROVED");
        assertThat(approved.get("version").asLong()).isEqualTo(1);
        assertThat(call("POST", "/v1/taxonomy/brands/" + id + "/approve", Map.of("reason", "again"), "\"1\"")
                .status()).isEqualTo(409);

        seller("org_q_b");
        assertThat(names(get("/v1/brands?category=" + dresses.id(), 200))).contains(typed);

        asStaff();
        JsonNode audit = get("/v1/taxonomy/audit?subject=" + id, 200);
        assertThat(audit.get(0).get("action").asString()).isEqualTo("BRAND_APPROVED");
        assertThat(audit.get(0).get("reason").asString()).isEqualTo("Real Lagos label");
        assertThat(jdbc.sql("select count(*) from platform_outbox where type = 'catalog.taxonomy.changed' and "
                + "aggregate_id = :id").param("id", id).query(Long.class).single()).isEqualTo(1);

        // reject a second one: no longer offered, products that hold it must pick another brand when edited
        seller("org_q_a");
        String junk = "Zzz Brand " + sfx;
        Product held = createDress(dresses, junk, "Q3-" + sfx);
        asStaff();
        String junkId = held.attributes().get("brand_id");
        JsonNode rejected = postIfMatch("/v1/taxonomy/brands/" + junkId + "/reject", Map.of("reason", "Not a real brand"),
                "\"0\"", 200);
        assertThat(rejected.get("status").asString()).isEqualTo("REJECTED");
        seller("org_q_a");
        assertThat(names(get("/v1/brands?category=" + dresses.id(), 200))).doesNotContain(junk);
        assertThatThrownBy(() -> products.update(held.id(), new UpdateProductCommand(null, held.title(), held.attributes(),
                held.images(), "", List.of(new VariantDraft(Map.of(), "Q3-" + sfx, null, true, 1299, 3)), null, null,
                held.details(), held.version(), null))).isInstanceOf(CatalogRuleViolation.class);
    }

    @Test
    void mergeRepointsProductsAndTheMergedBrandIsNoLongerOffered() throws Exception {
        String sfx = suffix();
        CategoryNode dresses = leaf("Fashion › Women › Dresses");
        seller("org_m_a");
        String typo = "Nikee " + sfx;
        Product p1 = createDress(dresses, typo, "M1-" + sfx);
        Product p2 = createDress(dresses, typo, "M2-" + sfx);
        String typoId = p1.attributes().get("brand_id");
        String nike = jdbc.sql("select id::text from brand where name = 'Nike'").query(String.class).single();
        asStaff();
        assertThat(call("POST", "/v1/taxonomy/brands/" + typoId + "/merge", Map.of("targetId", typoId, "reason",
                "self"), "\"0\"").status()).isEqualTo(422);
        JsonNode merged = postIfMatch("/v1/taxonomy/brands/" + typoId + "/merge", Map.of("targetId", nike, "reason",
                "Typo of Nike"), "\"0\"", 200);
        assertThat(merged.get("status").asString()).isEqualTo("MERGED_INTO");
        assertThat(merged.get("mergedIntoName").asString()).isEqualTo("Nike");
        seller("org_m_a");
        for (Product p : List.of(p1, p2)) {
            Product now = products.get(p.id());
            assertThat(now.attributes()).containsEntry("brand", "Nike").containsEntry("brand_id", nike);
            assertThat(now.version()).isGreaterThan(p.version());
        }
        assertThat(names(get("/v1/brands?category=" + dresses.id(), 200))).doesNotContain(typo).contains("Nike");
        assertThat(jdbc.sql("select count(*) from platform_outbox where type = 'catalog.product.updated' and "
                + "aggregate_id = :id").param("id", p1.id().toString()).query(Long.class).single()).isPositive();
        // an old form still carrying the merged brand's id lands on the target
        Product again = createDress(dresses, Map.of("brand", typo, "brand_id", typoId), "M3-" + sfx);
        assertThat(again.attributes()).containsEntry("brand", "Nike").containsEntry("brand_id", nike);
        asStaff();
        assertThat(call("POST", "/v1/taxonomy/brands/" + typoId + "/merge", Map.of("targetId", nike, "reason", "again"),
                "\"1\"").status()).isEqualTo(409);
        assertThat(get("/v1/taxonomy/audit?subject=" + typoId, 200).get(0).get("action").asString())
                .isEqualTo("BRAND_MERGED");
    }

    @Test
    void staffEditNameAndScopeAddBulkAndRetire() throws Exception {
        String sfx = suffix();
        asStaff();
        String fashion = nodeId("Fashion");
        String women = nodeId("Fashion › Women");
        JsonNode created = postIfMatch("/v1/taxonomy/brands", Map.of("name", "Ade Bakare " + sfx, "scope",
                List.of(women), "reason", "Lagos label"), null, 201);
        String id = created.get("id").asString();
        assertThat(created.get("status").asString()).isEqualTo("APPROVED");
        assertThat(call("POST", "/v1/taxonomy/brands", Map.of("name", "ade BAKARE " + sfx, "scope", List.of(women),
                "reason", "dup"), null).status()).isEqualTo(409);
        // nothing changed cannot be saved
        assertThat(call("PATCH", "/v1/taxonomy/brands/" + id, Map.of("name", "Ade Bakare " + sfx, "scope",
                List.of(women), "reason", "no change"), "\"0\"").status()).isEqualTo(422);
        JsonNode edited = postPatch("/v1/taxonomy/brands/" + id, Map.of("name", "Ade Bakare Studio " + sfx, "scope",
                List.of(women, fashion), "reason", "Wider and renamed"), "\"0\"");
        assertThat(edited.get("name").asString()).isEqualTo("Ade Bakare Studio " + sfx);
        assertThat(edited.get("scope")).hasSize(2);
        assertThat(edited.get("version").asLong()).isEqualTo(1);

        List<String> names = new ArrayList<>(List.of("Studio One " + sfx, "studio one " + sfx, "X", "Nike", "Studio Two " + sfx));
        JsonNode bulk = postIfMatch("/v1/taxonomy/brands/bulk", Map.of("names", names, "scope", List.of(fashion),
                "reason", "Seed list"), null, 200);
        assertThat(bulk.get("created").asInt()).isEqualTo(2);
        assertThat(bulk.get("existing").asInt()).isEqualTo(2);
        assertThat(bulk.get("invalid").asInt()).isEqualTo(1);

        JsonNode retired = postIfMatch("/v1/taxonomy/brands/" + id + "/retire", Map.of("reason", "Closed down"), "\"1\"",
                200);
        assertThat(retired.get("status").asString()).isEqualTo("RETIRED");
        seller("org_edit");
        assertThat(names(get("/v1/brands?category=" + leaf("Fashion › Women › Dresses").id(), 200)))
                .doesNotContain("Ade Bakare Studio " + sfx).contains("Studio One " + sfx);
    }

    @Test
    void onlyTaxonomyStaffMayUseTheBrandAdminApi() throws Exception {
        seller("org_perm");
        assertThat(call("GET", "/v1/taxonomy/brands", null, null).status()).isEqualTo(403);
        assertThat(call("POST", "/v1/taxonomy/brands", Map.of("name", "Nope", "scope", List.of(UUID.randomUUID()),
                "reason", "testing"), null).status()).isEqualTo(403);
        TestActors.as(new Actor("stf-mod", PrincipalType.STAFF, Optional.empty(), Set.of("platform.moderator"),
                Set.of("catalog:moderate")));
        assertThat(call("GET", "/v1/taxonomy/brands", null, null).status()).isEqualTo(403);
        asStaff();
        assertThat(call("GET", "/v1/taxonomy/brands", null, null).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ legacy data

    @Test
    void freeTextBrandsBecomeRegistryRowsScopedToTheirDepartment() throws Exception {
        String sfx = suffix();
        CategoryNode dresses = leaf("Fashion › Women › Dresses");
        CategoryNode spices = leaf("Groceries › Pantry › Spices & seasonings");
        CategoryNode fiction = leaf("Books › Books › Fiction");
        String lone = "Cotedivoire" + sfx;
        String shared = "Adire Shared" + sfx;
        Product loneProduct = createLegacy(dresses, "org_leg_a", lone, "L1-" + sfx);
        for (String org : List.of("org_leg_a", "org_leg_b", "org_leg_c")) {
            createLegacy(dresses, org, shared, "S-" + org + "-" + sfx);
        }
        Product nikeLower = createLegacy(dresses, "org_leg_b", "nike", "N-" + sfx);
        Product book = createLegacyBook(fiction, "org_leg_a", "Penguin" + sfx);
        Product groceryHomonym = createLegacy(spices, "org_leg_c", lone, "G-" + sfx);
        assertThat(loneProduct.attributes()).doesNotContainKey("brand_id");

        runBackfill();
        runBackfill(); // idempotent

        Product reloaded = reload(loneProduct.id());
        assertThat(reloaded.attributes()).containsEntry("brand", lone).containsKey("brand_id");
        Map<String, Object> pending = jdbc.sql("select status, created_by_org from brand where id = cast(:id as uuid)")
                .param("id", reloaded.attributes().get("brand_id")).query().singleRow();
        assertThat(pending).containsEntry("status", "PENDING").containsEntry("created_by_org", "org_leg_a");
        // a department-level scope: Fashion only, so Groceries never offers it to the seller who typed it for a dress
        assertThat(jdbc.sql("select c.path from brand_scope s join category c on c.id = s.node_id where s.brand_id = "
                + "cast(:id as uuid)").param("id", reloaded.attributes().get("brand_id")).query(String.class).list())
                .containsExactly("Fashion");
        assertThat(reload(groceryHomonym.id()).attributes().get("brand_id"))
                .isNotEqualTo(reloaded.attributes().get("brand_id"));
        assertThat(jdbc.sql("select c.path from brand_scope s join category c on c.id = s.node_id where s.brand_id = "
                + "cast(:id as uuid)").param("id", reload(groceryHomonym.id()).attributes().get("brand_id"))
                .query(String.class).list()).containsExactly("Groceries");

        // three distinct sellers: approved
        String sharedId = reload(loneProductSharedId(dresses, shared)).attributes().get("brand_id");
        assertThat(jdbc.sql("select status from brand where id = cast(:id as uuid)").param("id", sharedId)
                .query(String.class).single()).isEqualTo("APPROVED");
        // a staff-listed brand is linked, not duplicated
        assertThat(reload(nikeLower.id()).attributes()).containsEntry("brand", "Nike");
        assertThat(jdbc.sql("select count(*) from brand where normalized_name = 'nike'").query(Long.class).single())
                .isEqualTo(1);
        // books are left alone
        assertThat(reload(book.id()).attributes()).doesNotContainKey("brand_id");

        seller("org_leg_a");
        assertThat(names(get("/v1/brands?category=" + dresses.id(), 200))).contains(lone, shared);
        assertThat(names(get("/v1/brands?category=" + leaf("Groceries › Pantry › Snacks").id(), 200)))
                .doesNotContain(shared);
        seller("org_leg_d");
        assertThat(names(get("/v1/brands?category=" + dresses.id(), 200))).contains(shared).doesNotContain(lone);
    }

    // ------------------------------------------------------------------ helpers

    private UUID loneProductSharedId(CategoryNode node, String brand) {
        return jdbc.sql("select id from product where category_id = :c and attributes ->> 'brand' = :b limit 1")
                .param("c", node.id()).param("b", brand).query(UUID.class).single();
    }

    private void runBackfill() throws IOException, SQLException {
        String script = new ClassPathResource("db/migration/V14__legacy_brand_backfill.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute(script);
            }
            c.commit();
        }
    }

    /** A product as it was before the registry: a free-text brand and no brand id. */
    private Product createLegacy(CategoryNode node, String org, String brand, String sku) {
        seller(org);
        Product p = node.path().startsWith("Fashion") ? createDress(node, "Nike", sku)
                : create(node, fill(categories.productType(node.productTypeCode())), sku);
        jdbc.sql("update product set attributes = (attributes - 'brand_id') || jsonb_build_object('brand', cast(:b as text))"
                + " where id = :id").param("b", brand).param("id", p.id()).update();
        return reload(p.id());
    }

    private Product createLegacyBook(CategoryNode node, String org, String brand) {
        seller(org);
        Map<String, String> attrs = new LinkedHashMap<>(fill(categories.productType("book")));
        Product p = products.create(new CreateProductCommand(node.id(), null, "Legacy book " + suffix(), attrs,
                List.of(IMAGE), "", List.of(new VariantDraft(Map.of(), "LB-" + suffix(), isbn(), false, 999,
                        2)), "USD"));
        jdbc.sql("update product set attributes = attributes || jsonb_build_object('brand', cast(:b as text)) "
                + "where id = :id").param("b", brand).param("id", p.id()).update();
        return reload(p.id());
    }

    private Product reload(UUID id) {
        TestActors.reset();
        return products.get(id);
    }

    private Product createDress(CategoryNode node, String brand, String sku) {
        return createDress(node, Map.of("brand", brand), sku);
    }

    private Product createDress(CategoryNode node, Map<String, String> brandAttrs, String sku) {
        Map<String, String> attrs = new LinkedHashMap<>(fill(categories.productType(node.productTypeCode())));
        attrs.remove("brand");
        attrs.putAll(brandAttrs);
        return create(node, attrs, sku);
    }

    private Product create(CategoryNode node, Map<String, String> attrs, String sku) {
        return products.create(new CreateProductCommand(node.id(), null, "Ankara piece " + sku, attrs, List.of(IMAGE), "",
                List.of(new VariantDraft(Map.of(), sku, null, true, 6499, 5)), "USD"));
    }

    /** A value for every required attribute of the type (the first option of a choice). */
    private static Map<String, String> fill(ProductType type) {
        Map<String, String> out = new LinkedHashMap<>();
        for (AttributeDefinition a : type.applicableAttributes()) {
            if (!a.required()) {
                continue;
            }
            out.put(a.key(), switch (a.kind()) {
                case ENUM -> a.options().getFirst();
                case NUMBER -> "12" + (a.units().isEmpty() ? "" : " " + a.units().getFirst());
                default -> "Sample " + a.key();
            });
        }
        return out;
    }

    private AttributeDefinition brand(String typeCode) {
        return categories.productType(typeCode).brandAttribute().orElseThrow();
    }

    private CategoryNode leaf(String path) {
        return categories.tree().stream().filter(n -> n.level() == CategoryLevel.PRODUCT_TYPE && n.path().equals(path))
                .findFirst().orElseThrow();
    }

    private String nodeId(String path) {
        return categories.tree(true).stream().filter(n -> n.path().equals(path)).findFirst().orElseThrow().id()
                .toString();
    }

    private String department(String leafId) {
        CategoryNode leaf = categories.tree(true).stream().filter(n -> n.id().toString().equals(leafId)).findFirst()
                .orElseThrow();
        return categories.tree(true).stream().filter(n -> n.parentId() == null
                && leaf.path().startsWith(n.path() + " › ")).findFirst().orElseThrow().id().toString();
    }

    /** A new Electronics department with a Phones category and a Smartphones product type; returns the leaf id. */
    private String electronicsLeaf(String sfx) throws Exception {
        asStaff();
        String dept = staffPost("/v1/taxonomy/nodes", Map.of("level", "DEPARTMENT", "name", "Electronics " + sfx,
                "reason", "Fixture"), 201).get("id").asString();
        String cat = staffPost("/v1/taxonomy/nodes", Map.of("level", "CATEGORY", "parentId", dept, "name", "Phones",
                "reason", "Fixture"), 201).get("id").asString();
        return staffPost("/v1/taxonomy/nodes", Map.of("level", "PRODUCT_TYPE", "parentId", cat, "name", "Smartphones",
                "productType", "electronics", "reason", "Fixture"), 201).get("id").asString();
    }

    private void seller(String org) {
        TestActors.as(new Actor("member-" + org, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of("seller.owner"),
                Set.of("catalog:write")));
    }

    private void asStaff() {
        TestActors.as(STAFF);
    }

    private JsonNode staffPost(String path, Object body, int expected) throws Exception {
        asStaff();
        return post(path, body, expected);
    }

    private JsonNode get(String path, int expected) throws Exception {
        Reply r = call("GET", path, null, null);
        assertThat(r.status()).as("GET %s: %s", path, r.body()).isEqualTo(expected);
        return r.body();
    }

    private JsonNode post(String path, Object body, int expected) throws Exception {
        Reply r = call("POST", path, body, null);
        assertThat(r.status()).as("POST %s: %s", path, r.body()).isEqualTo(expected);
        return r.body();
    }

    private JsonNode postIfMatch(String path, Object body, String ifMatch, int expected) throws Exception {
        Reply r = call("POST", path, body, ifMatch);
        assertThat(r.status()).as("POST %s: %s", path, r.body()).isEqualTo(expected);
        return r.body();
    }

    private JsonNode postPatch(String path, Object body, String ifMatch) throws Exception {
        Reply r = call("PATCH", path, body, ifMatch);
        assertThat(r.status()).as("PATCH %s: %s", path, r.body()).isEqualTo(200);
        return r.body();
    }

    private static List<String> names(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.get("brands").forEach(b -> out.add(b.get("name").asString()));
        return out;
    }

    /** A valid ISBN-13 no other test uses. */
    private static String isbn() {
        String body = "978" + String.format("%09d", Math.abs(UUID.randomUUID().getLeastSignificantBits()) % 1_000_000_000L);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (body.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return body + (10 - sum % 10) % 10;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
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
