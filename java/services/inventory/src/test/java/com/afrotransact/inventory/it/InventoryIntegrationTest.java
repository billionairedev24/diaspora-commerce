package com.afrotransact.inventory.it;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.inventory.application.ReceiptResult;
import com.afrotransact.inventory.application.ReceiveCommand;
import com.afrotransact.inventory.application.ReceivingService;
import com.afrotransact.inventory.application.ReconciliationService;
import com.afrotransact.inventory.domain.ReconciliationReport;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container and one Spring context (random port) shared by every integration test class.
 * Tests use unique SKUs so they never interfere; scheduled jobs are off and called directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.inventory.jobs.enabled=false",
    "at.consumers.enabled=false",
    "spring.datasource.hikari.maximum-pool-size=20"
})
@ActiveProfiles("local")
@Import(TestActors.class)
abstract class InventoryIntegrationTest {

    /** Set {@code AT_IT_POSTGRES_URL} (jdbc:postgresql://…) to run without Docker; user and password from
     * {@code AT_IT_POSTGRES_USER} / {@code AT_IT_POSTGRES_PASSWORD} (default postgres / empty). */
    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();
    /** catalog (unit labels) and carrier-gateway (inbound labels, tracking) stand-in (S257). */
    static final WireMockServer DOWNSTREAM = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static final String NODE = "HOU-FC";
    static final String RETAIL = "org_afrotransact_retail";

    static {
        if (EXTERNAL_DB == null) {
            POSTGRES.start();
        }
        DOWNSTREAM.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_DB != null ? EXTERNAL_DB : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "") : POSTGRES.getPassword());
        registry.add("at.inventory.catalog.base-url", DOWNSTREAM::baseUrl);
        registry.add("at.inventory.carrier-gateway.base-url", DOWNSTREAM::baseUrl);
        registry.add("at.inventory.catalog.timeout", () -> "5s");
    }

    ReceivingService receiving;
    ReconciliationService reconciliation;
    JdbcClient jdbc;

    @BeforeEach
    void wireBase(@Autowired ReceivingService receivingService, @Autowired ReconciliationService reconciliationService,
                  @Autowired JdbcClient jdbcClient) {
        this.receiving = receivingService;
        this.reconciliation = reconciliationService;
        this.jdbc = jdbcClient;
    }

    @AfterEach
    void resetActor() {
        TestActors.reset();
    }

    static String sku(String prefix) {
        return (prefix + "-" + UUID.randomUUID().toString().substring(0, 8)).toUpperCase(Locale.ROOT);
    }

    static LocalDate today() {
        return LocalDate.now();
    }

    /** One lot line made 30 days ago and expiring in {@code expiresInDays} (shelf life derived from the dates). */
    static ReceiveCommand.Line line(String sku, String lotNo, int expiresInDays, int good, int damaged, long cost) {
        return new ReceiveCommand.Line(sku, lotNo, today().minusDays(30),
                today().plusDays(expiresInDays), good, damaged, "A-01-01", cost, "USD", null);
    }

    ReceiptResult receive(String nodeId, ReceiveCommand.Line... lines) {
        return receiving.receive(new ReceiveCommand("GRN-" + UUID.randomUUID(), nodeId, null, List.of(lines)),
                UUID.randomUUID().toString());
    }

    ReceiptResult stock(String sku, int units) {
        return receive(NODE, line(sku, "L-" + UUID.randomUUID().toString().substring(0, 6), 500, units, 0, 250));
    }

    int position(String sku, String column) {
        return jdbc.sql("select " + column + " from inventory_position where sku = :sku and node_id = :node")
                .param("sku", sku).param("node", NODE).query(Integer.class).single();
    }

    long ledgerRows(String sku, String type) {
        return jdbc.sql("select count(*) from stock_movement where sku = :sku and type = :type")
                .param("sku", sku).param("type", type).query(Long.class).single();
    }

    long events(String type, String aggregateId) {
        return jdbc.sql("select count(*) from platform_outbox where type = :type and aggregate_id = :id")
                .param("type", type).param("id", aggregateId).query(Long.class).single();
    }

    ReconciliationReport reconcile() {
        return reconciliation.reconcile();
    }
}
