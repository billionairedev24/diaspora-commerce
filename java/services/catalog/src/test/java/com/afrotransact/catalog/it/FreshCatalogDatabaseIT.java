package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.afrotransact.platform.test.PostgresTestContainer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An empty PostgreSQL database, the catalog's migrations and nothing else (no Spring context, no importer, no other test
 * before it): what the baseline ({@code V1__baseline.sql} plus the generated {@code V1_1__initial_data.sql}) leaves behind.
 * 13 departments, 74 categories with their tax profile id and status, 77 product-type leaves, the product types with
 * their sizing, the department compliance, the starter brands and their scopes, and no other rows.
 * {@code AT_IT_POSTGRES_URL=jdbc:postgresql://…} runs it on that server (in a throwaway database it creates and drops).
 */
class FreshCatalogDatabaseIT {

    private static final String EXTERNAL = System.getenv("AT_IT_POSTGRES_URL");
    private static final String USER = System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static PostgreSQLContainer container;
    private static String scratchDatabase;
    private static JdbcClient jdbc;

    @BeforeAll
    static void migrateAnEmptyDatabase() throws SQLException {
        String url;
        String user;
        String password;
        if (EXTERNAL == null) {
            container = PostgresTestContainer.create();
            container.start();
            url = container.getJdbcUrl();
            user = container.getUsername();
            password = container.getPassword();
        } else {
            scratchDatabase = "catalog_fresh_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection c = DriverManager.getConnection(EXTERNAL, USER, PASSWORD);
                 var st = c.createStatement()) {
                st.execute("create database " + scratchDatabase);
            }
            url = EXTERNAL.substring(0, EXTERNAL.lastIndexOf('/') + 1) + scratchDatabase;
            user = USER;
            password = PASSWORD;
        }
        Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/platform", "classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(new DriverManagerDataSource(url, user, password));
    }

    @AfterAll
    static void dropTheScratchDatabase() throws SQLException {
        if (container != null) {
            container.stop();
        }
        if (scratchDatabase != null) {
            try (Connection c = DriverManager.getConnection(EXTERNAL, USER, PASSWORD);
                 var st = c.createStatement()) {
                st.execute("drop database if exists " + scratchDatabase + " with (force)");
            }
        }
    }

    @Test
    void theHistoryIsTheBaselineAndTheInitialDataOnly() {
        List<Map<String, Object>> history = jdbc.sql(
                "select version, type, success from flyway_schema_history order by installed_rank").query().listOfRows();
        assertThat(history).extracting(r -> r.get("version")).containsExactly("0.0.1", "1", "1.1");
        assertThat(history).extracting(r -> r.get("type")).containsOnly("SQL");
        assertThat(history).extracting(r -> r.get("success")).containsOnly(true);
    }

    @Test
    void theTreeHas13Departments74CategoriesAnd77ProductTypeLeaves() {
        assertThat(count("select count(*) from category where level = 'DEPARTMENT' and parent_id is null")).isEqualTo(13);
        assertThat(count("select count(*) from category where level = 'CATEGORY'")).isEqualTo(74);
        assertThat(count("select count(*) from category where level = 'PRODUCT_TYPE'")).isEqualTo(77);
        assertThat(count("select count(*) from category where not active or retired_at is not null")).isZero();
        assertThat(count("select count(*) from category where version <> 0")).isZero();
        assertThat(count("select count(*) from category c where level = 'CATEGORY' and not exists "
                + "(select 1 from category d where d.id = c.parent_id and d.level = 'DEPARTMENT')")).isZero();
        assertThat(count("select count(*) from category c where level = 'PRODUCT_TYPE' and not exists "
                + "(select 1 from category d where d.id = c.parent_id and d.level = 'CATEGORY')")).isZero();
        assertThat(count("select count(*) from category where level = 'PRODUCT_TYPE' and product_type_code is null"))
                .isZero();
        assertThat(jdbc.sql("select path from category where code = 'adult-clothing-footwear-dress'")
                .query(String.class).optional()).contains("Fashion & Accessories › Adult clothing & footwear › Women's dresses");
    }

    @Test
    void everyCategoryHasItsTaxProfileIdAndStatusExceptTheProvisionalFabrics() {
        assertThat(count("select count(*) from category where level = 'CATEGORY' and tax_profile_id = code")).isEqualTo(73);
        Map<String, Object> fabrics = jdbc.sql("select tax_profile_id, tax_status from category "
                + "where code = 'fabrics-textiles'").query().singleRow();
        assertThat(fabrics.get("tax_profile_id")).isNull();
        assertThat(fabrics.get("tax_status")).isEqualTo("NEEDS_APPROVAL");
        Map<String, Long> byStatus = new TreeMap<>();
        jdbc.sql("select tax_status, count(*) as n from category where level = 'CATEGORY' group by tax_status")
                .query((rs, i) -> byStatus.put(rs.getString(1), rs.getLong(2))).list();
        assertThat(byStatus).containsExactly(Map.entry("ACTIVE", 57L), Map.entry("BLOCKED_BUNDLE", 1L),
                Map.entry("CONFLICT_OPEN", 2L), Map.entry("NEEDS_APPROVAL", 8L), Map.entry("REFINE_CODE", 6L));
        assertThat(count("select count(*) from category where level <> 'CATEGORY' and (tax_profile_id is not null "
                + "or tax_status is not null)")).isZero();
    }

    @Test
    void theProductTypesAreThereWithTheirSchemasAndFashionIsSized() throws Exception {
        assertThat(count("select count(*) from product_type")).isEqualTo(79);
        assertThat(jdbc.sql("select code from product_type where code in ('custom', 'electronics') order by code")
                .query(String.class).list()).containsExactly("custom", "electronics");
        // every product-type leaf points at a stored type
        assertThat(count("select count(distinct product_type_code) from category where level = 'PRODUCT_TYPE'"))
                .isEqualTo(77);
        assertThat(jdbc.sql("select identifier_rule from product_type where code = 'printed-books'")
                .query(String.class).single()).isEqualTo("ISBN");
        JsonNode bakery = JSON.readTree(jdbc.sql("select traits::text from product_type where code = 'bakery-products'")
                .query(String.class).single());
        assertThat(bakery.get("food").asBoolean()).isTrue();
        assertThat(bakery.get("preorder").asBoolean()).isTrue();
        for (String code : List.of("dress", "top", "menwear", "footwear", "kids")) {
            JsonNode traits = JSON.readTree(jdbc.sql("select traits::text from product_type where code = :c")
                    .param("c", code).query(String.class).single());
            assertThat(traits.path("sizing").path("sizeDimension").asString()).as(code).isEqualTo("Size");
            assertThat(traits.path("sizing").path("sizeChartRequired").asBoolean()).as(code).isFalse();
            assertThat(traits.path("sizing").path("suggestedMeasurements").size()).as(code).isPositive();
        }
        assertThat(count("select count(*) from product_type where jsonb_exists(traits, 'sizing')")).isEqualTo(5);
        // tax questions are typed attributes (dataset-owned), with the details before them
        JsonNode attributes = JSON.readTree(jdbc.sql("select attributes::text from product_type where code = 'bakery-products'")
                .query(String.class).single());
        assertThat(attributes.findValuesAsString("key")).contains("brand", "ingredients", "sold_heated");
        assertThat(count("select count(*) from product_type where attributes @> '[{\"taxFact\": true}]'")).isPositive();
        assertThat(count("select count(*) from product_type where version <> 0")).isZero();
    }

    @Test
    void onlyTheBeautyDepartmentCarriesComplianceData() {
        assertThat(jdbc.sql("select code || '=' || compliance from category where compliance is not null")
                .query(String.class).list()).containsExactly("beauty-personal-care=BEAUTY_LABELLING");
    }

    @Test
    void theStarterBrandsAreApprovedAndScopedToTheTree() {
        assertThat(count("select count(*) from brand")).isEqualTo(56);
        assertThat(count("select count(*) from brand where status <> 'APPROVED' or created_by_org is not null "
                + "or merged_into_id is not null")).isZero();
        assertThat(count("select count(*) from brand where normalized_name <> brand_key(name)")).isZero();
        assertThat(count("select count(*) from brand b where not exists (select 1 from brand_scope s where s.brand_id = b.id)"))
                .isZero();
        assertThat(scopes("Nike")).containsExactly("adult-clothing-footwear", "childrens-clothing");
        assertThat(scopes("Vlisco")).containsExactly("fabrics-textiles");
        assertThat(scopes("Maggi")).containsExactly("groceries-food");
        assertThat(scopes("SheaMoisture")).containsExactly("beauty-personal-care");
        assertThat(count("select count(*) from brand_scope")).isEqualTo(72);
    }

    @Test
    void nothingElseIsSeeded() {
        for (String table : List.of("product", "product_variant", "product_draft", "product_import", "product_import_row",
                "product_flag", "product_takedown", "product_review", "product_review_report", "product_rating",
                "product_data_lookup", "category_request", "size_chart", "taxonomy_audit", "unit_label",
                "variant_tax_evidence", "org_listing_suppression", "listing_enforcement_run", "platform_outbox",
                "platform_inbox")) {
            assertThat(count("select count(*) from " + table)).as(table).isZero();
        }
    }

    private static long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static List<String> scopes(String brand) {
        return jdbc.sql("select distinct c.code from brand b join brand_scope s on s.brand_id = b.id "
                + "join category c on c.id = s.node_id where b.name = :n order by c.code").param("n", brand)
                .query(String.class).list();
    }
}
