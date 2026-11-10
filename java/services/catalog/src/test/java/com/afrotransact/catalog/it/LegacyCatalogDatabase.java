package com.afrotransact.catalog.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import com.afrotransact.platform.test.PostgresTestContainer;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Builds the database an existing environment has: the migrations up to a given version (V2 seeds the initial tree, V19
 * retires it) plus products, drafts, size charts, brand scopes and a category request on the initial tree. The upgrade
 * ITs then start the service on it, which runs what is still pending (V19 and V20, or V20 alone when the database
 * already recorded V19), the same way the owner's local catalogue database upgrades.
 *
 * <p>{@code AT_IT_POSTGRES_URL=jdbc:postgresql://host:port/db} runs on that PostgreSQL instead of a container (a database
 * of its own is created inside it), as the other ITs do.
 */
final class LegacyCatalogDatabase {

    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = EXTERNAL_DB == null ? start() : null;

    /** The initial tree's nodes by the deterministic id V2 gave them (uuid5 of the path). */
    static final String HERBAL_TYPE = "fb5368ba-961f-50aa-9d69-10c2870f91a6";
    static final String DRESSES_TYPE = "9155c8ee-935b-54b5-9f67-a7b03a9f7479";
    static final String GRAINS_TYPE = "0ae4c60a-10dd-5bf5-b077-7d90097fc8b1";
    static final String KIDS_TYPE = "208165be-3b89-5583-a1c3-2d5a84be2417";
    static final String FICTION_TYPE = "ffea101f-c4d9-5a3d-8844-702a2fc5f2dc";
    static final String SNACKS_TYPE = "b263f453-e2a5-53f4-8da1-12a6f59d8e86";
    static final String WELLNESS_CATEGORY = "08390df5-f9af-5c18-abf1-ec2d785bb1de";
    static final String WOMEN_CATEGORY = "adc666f2-e3bd-5495-a669-f31d64177cb3";
    static final String HEALTH_DEPARTMENT = "67761b06-a83e-5e91-8c32-24a365b9a429";
    static final String GROCERIES_DEPARTMENT = "a5d48106-da2f-528b-a4a4-ec8a1f36d5ff";

    static final String HERBAL_ATIN = "AT00000001";
    static final String DRESS_ATIN = "AT00000002";
    static final String GRAINS_ATIN = "AT00000003";
    static final String LEGACY_BRAND = "00000000-0000-4000-8000-0000000000b1";

    private LegacyCatalogDatabase() {
    }

    private static PostgreSQLContainer start() {
        PostgreSQLContainer container = PostgresTestContainer.create();
        container.start();
        return container;
    }

    /** The JDBC URL of the container or of the external instance. */
    static String adminUrl() {
        return EXTERNAL_DB != null ? EXTERNAL_DB : POSTGRES.getJdbcUrl();
    }

    static String user() {
        return EXTERNAL_DB != null ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres") : POSTGRES.getUsername();
    }

    static String password() {
        return EXTERNAL_DB != null ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "") : POSTGRES.getPassword();
    }

    /**
     * Creates a database named {@code name}, migrates it to V18, loads the legacy data and migrates on to {@code targetVersion}.
     *
     * @return the JDBC URL of the new database
     */
    static String create(String name, String targetVersion) {
        try (Connection admin = DriverManager.getConnection(adminUrl(), user(), password());
             Statement st = admin.createStatement()) {
            st.execute("drop database if exists " + name + " with (force)");
            st.execute("create database " + name);
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot create database " + name, ex);
        }
        String url = adminUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        migrate(url, "18");
        try (Connection c = DriverManager.getConnection(url, user(), password()); Statement st = c.createStatement()) {
            for (String sql : legacyData()) {
                st.execute(sql);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot load the legacy data", ex);
        }
        if (!"18".equals(targetVersion)) {
            migrate(url, targetVersion);
        }
        return url;
    }

    /** A database no one has touched: created empty, the service migrates it from V1 to the latest version. */
    static String createEmpty(String name) {
        try (Connection admin = DriverManager.getConnection(adminUrl(), user(), password());
             Statement st = admin.createStatement()) {
            st.execute("drop database if exists " + name + " with (force)");
            st.execute("create database " + name);
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot create database " + name, ex);
        }
        return adminUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
    }

    private static void migrate(String url, String targetVersion) {
        Flyway.configure().dataSource(url, user(), password())
                .locations("classpath:db/platform", "classpath:db/migration")
                .target(targetVersion).load().migrate();
    }

    private static String product(String id, String atin, String type, String node, String status, String title) {
        return "insert into product (id, atin, owner_kind, owner_org, category_id, product_type, title, attributes, images,"
                + " variation_theme, status, created_at, updated_at, version) values ('" + id + "', '" + atin
                + "', 'SELLER', 'org_legacy_seller', '" + node + "', '" + type + "', '" + title + "', '{}'::jsonb,"
                + " '[{\"url\":\"https://utfs.io/f/" + atin + ".jpg\",\"width\":1600,\"height\":1600,\"bytes\":300000,"
                + "\"contentType\":\"image/jpeg\"}]'::jsonb, '', '" + status + "', now(), now(), 0)";
    }

    private static String variant(String id, String product, String atin, String sku) {
        return "insert into product_variant (id, product_id, atin, owner_org, seller_sku, options, price_minor, currency,"
                + " quantity, position) values ('" + id + "', '" + product + "', '" + atin + "', 'org_legacy_seller', '" + sku
                + "', '{}'::jsonb, 1999, 'USD', 5, 0)";
    }

    /**
     * What an existing database holds on the initial tree. Rows on the nodes under "Health > Wellness" are the ones that
     * make V19 keep the category whose code {@code health-wellness} the new tree uses for its Health &amp; Wellness
     * department.
     */
    private static String[] legacyData() {
        return new String[] {
            product("00000000-0000-4000-8000-000000000001", HERBAL_ATIN, "grocery", HERBAL_TYPE, "LIVE", "Hibiscus remedy"),
            variant("00000000-0000-4000-8000-0000000000a1", "00000000-0000-4000-8000-000000000001", "AT0000001A", "HERBAL-1"),
            product("00000000-0000-4000-8000-000000000002", DRESS_ATIN, "dress", DRESSES_TYPE, "LIVE", "Ankara dress"),
            variant("00000000-0000-4000-8000-0000000000a2", "00000000-0000-4000-8000-000000000002", "AT0000002A", "DRESS-1"),
            product("00000000-0000-4000-8000-000000000003", GRAINS_ATIN, "grocery", GRAINS_TYPE, "IN_REVIEW", "Basmati"),
            variant("00000000-0000-4000-8000-0000000000a3", "00000000-0000-4000-8000-000000000003", "AT0000003A", "RICE-1"),
            "insert into product_draft (id, owner_org, created_by, title, category_path, payload, created_at, updated_at)"
                    + " values ('00000000-0000-4000-8000-0000000000d1', 'org_legacy_seller', 'user_1', 'Half-finished dress',"
                    + " 'Fashion › Women › Dresses', '{}'::jsonb, now(), now())",
            "insert into size_chart (id, owner_org, name, unit_system, product_type_code, category_id, columns, rows,"
                    + " created_by, created_at, updated_at) values ('00000000-0000-4000-8000-0000000000c1',"
                    + " 'org_legacy_seller', 'Kids sizes', 'CM', 'kids', '" + KIDS_TYPE + "', '[\"Chest\"]'::jsonb,"
                    + " '[{\"size\":\"S\",\"values\":[\"60\"]}]'::jsonb, 'user_1', now(), now())",
            "insert into brand (id, name, normalized_name, status) values ('" + LEGACY_BRAND + "', 'Legacy Wax Co',"
                    + " 'legacywaxco', 'APPROVED')",
            "insert into brand_scope (brand_id, node_id) values ('" + LEGACY_BRAND + "', '" + WOMEN_CATEGORY + "'),"
                    + " ('" + LEGACY_BRAND + "', '" + GROCERIES_DEPARTMENT + "')",
            "insert into category_request (id, reference, requested_by_org, parent_path, name, example_product,"
                    + " details_needed, status, resolved_category_id, created_at) values"
                    + " ('00000000-0000-4000-8000-0000000000e1', 'CR-LEGACY-1', 'org_legacy_seller', 'Books › Books',"
                    + " 'Poetry', 'A poetry book', 'ISBN', 'APPROVED', '" + FICTION_TYPE + "', now())"
        };
    }
}
