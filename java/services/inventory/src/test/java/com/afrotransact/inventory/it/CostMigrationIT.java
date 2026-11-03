package com.afrotransact.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V7 on a database that already holds lots: they are backfilled as US dollars at rate 1 with the whole cost under
 * "goods", and the new constraints hold. Runs in its own schema, migrating to V6, adding old-shape rows, then to the
 * latest version.
 */
class CostMigrationIT {

    private static final String LOCATIONS_PLATFORM = "classpath:db/platform";
    private static final String LOCATIONS_SERVICE = "classpath:db/migration";

    private static String baseUrl() {
        return InventoryIntegrationTest.EXTERNAL_DB != null ? InventoryIntegrationTest.EXTERNAL_DB
                : InventoryIntegrationTest.POSTGRES.getJdbcUrl();
    }

    private static String user() {
        return InventoryIntegrationTest.EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres")
                : InventoryIntegrationTest.POSTGRES.getUsername();
    }

    private static String password() {
        return InventoryIntegrationTest.EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "")
                : InventoryIntegrationTest.POSTGRES.getPassword();
    }

    @Test
    void existingLotsAndAllocationsAreBackfilledAsUsdAtRateOne() {
        String schema = "mig_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String url = baseUrl() + (baseUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema;
        Flyway before = Flyway.configure().dataSource(baseUrl(), user(), password()).schemas(schema)
                .createSchemas(true).locations(LOCATIONS_PLATFORM, LOCATIONS_SERVICE).target("6").load();
        before.migrate();
        JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(url, user(), password()));
        UUID usdLot = UUID.randomUUID();
        UUID oddLot = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        jdbc.sql("""
                insert into inventory_position (sku, node_id, owner_org, on_hand, updated_at)
                values ('MIG-1', 'HOU-FC', 'org_afrotransact_retail', 15, now())
                """).update();
        insertLot(jdbc, usdLot, "L-MIG-1", 250, "USD");
        insertLot(jdbc, oddLot, "L-MIG-2", 40_000, "GHS");
        jdbc.sql("insert into reservation (id, order_id, status, created_at, updated_at) "
                + "values (:id, 'ORD-MIG', 'COMMITTED', now(), now())").param("id", reservation).update();
        jdbc.sql("insert into reservation_line (reservation_id, line_no, sku, node_id, qty) "
                + "values (:id, 1, 'MIG-1', 'HOU-FC', 3)").param("id", reservation).update();
        jdbc.sql("insert into reservation_allocation (reservation_id, line_no, lot_id, lot_no, qty, "
                + "unit_landed_cost_minor, currency) values (:id, 1, :lot, 'L-MIG-1', 3, 250, 'USD')")
                .param("id", reservation).param("lot", usdLot).update();

        Flyway.configure().dataSource(baseUrl(), user(), password()).schemas(schema)
                .locations(LOCATIONS_PLATFORM, LOCATIONS_SERVICE).load().migrate();

        assertThat(jdbc.sql("""
                select currency || '|' || unit_landed_cost_minor || '|' || unit_cost_usd_minor || '|'
                       || fx_rate_to_usd || '|' || fx_rate_source || '|' || cost_goods_minor || '|'
                       || cost_freight_minor || '|' || cost_duty_minor || '|' || cost_other_minor
                from lot where lot_no = 'L-MIG-1'
                """).query(String.class).single()).isEqualTo("USD|250|250|1.0000000000|BASE|250|0|0|0");
        assertThat(jdbc.sql("select fx_rate_date is not null from lot where lot_no = 'L-MIG-1'")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select currency || '|' || unit_cost_usd_minor from lot where lot_no = 'L-MIG-2'")
                .query(String.class).single()).as("a lot that was never in USD is relabelled, not left inconsistent")
                .isEqualTo("USD|40000");
        assertThat(jdbc.sql("select unit_cost_usd_minor from reservation_allocation where reservation_id = :id")
                .param("id", reservation).query(Long.class).single()).isEqualTo(250);

        assertThatThrownBy(() -> jdbc.sql("update lot set cost_freight_minor = 5 where lot_no = 'L-MIG-1'").update())
                .as("components must add up to the unit cost").hasMessageContaining("lot_cost_components_add_up");
        assertThatThrownBy(() -> jdbc.sql("update lot set fx_rate_to_usd = 1.1 where lot_no = 'L-MIG-1'").update())
                .as("a US dollar lot has rate 1").hasMessageContaining("lot_usd_has_rate_one");
        assertThatThrownBy(() -> jdbc.sql("update lot set fx_rate_to_usd = 0 where lot_no = 'L-MIG-1'").update())
                .hasMessageContaining("fx_rate_to_usd");
        assertThat(jdbc.sql("select rate_to_usd from exchange_rate").query(BigDecimal.class).list()).isEmpty();
        jdbc.sql("drop schema " + schema + " cascade").update();
    }

    private static void insertLot(JdbcClient jdbc, UUID id, String lotNo, long cost, String currency) {
        jdbc.sql("""
                insert into lot (id, sku, lot_no, node_id, owner_org, qty_on_hand, received_at, unit_landed_cost_minor,
                                 currency, status, updated_at)
                values (:id, 'MIG-1', :lotNo, 'HOU-FC', 'org_afrotransact_retail', 5, now(), :cost, :currency,
                        'SELLABLE', now())
                """).param("id", id).param("lotNo", lotNo).param("cost", cost).param("currency", currency).update();
    }
}
