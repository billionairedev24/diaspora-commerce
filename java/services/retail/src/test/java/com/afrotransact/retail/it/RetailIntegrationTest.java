package com.afrotransact.retail.it;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.retail.application.CreatePoCommand;
import com.afrotransact.retail.application.ItemCommand;
import com.afrotransact.retail.application.ItemService;
import com.afrotransact.retail.application.PoLineInput;
import com.afrotransact.retail.application.PurchaseOrderService;
import com.afrotransact.retail.application.SupplierCommand;
import com.afrotransact.retail.application.SupplierService;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.StorageType;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierContact;
import com.afrotransact.retail.domain.SupplierItem;
import com.afrotransact.retail.domain.SupplierStatus;
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
 * One PostgreSQL container, one WireMock (identity token, catalog, inventory) and one Spring context shared by every
 * IT class. Tests create their own suppliers, items and POs so they never interfere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.retail.consumers.enabled=false",
    "at.retail.auth.client-secret=it-secret",
    "at.retail.inventory.timeout=5s",
    // S225: tests drive the dispatch job directly
    "at.retail.dispatch.job-enabled=false"
})
@ActiveProfiles("local")
@Import({TestActors.class, FakeEdi.class})
abstract class RetailIntegrationTest {

    /** Set {@code AT_IT_POSTGRES_URL} (jdbc:postgresql://…) to run without Docker; user and password from
     * {@code AT_IT_POSTGRES_USER} / {@code AT_IT_POSTGRES_PASSWORD} (default postgres / empty). */
    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();
    static final WireMockServer DOWNSTREAM = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        if (EXTERNAL_DB == null) {
            POSTGRES.start();
        }
        DOWNSTREAM.start();
        Stubs.install(DOWNSTREAM);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_DB != null ? EXTERNAL_DB : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "") : POSTGRES.getPassword());
        registry.add("at.retail.catalog.base-url", DOWNSTREAM::baseUrl);
        registry.add("at.retail.inventory.base-url", DOWNSTREAM::baseUrl);
        registry.add("at.retail.media.base-url", DOWNSTREAM::baseUrl);
        registry.add("at.retail.auth.token-uri", () -> DOWNSTREAM.baseUrl() + "/oauth2/token");
    }

    static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    SupplierService suppliers;
    ItemService items;
    PurchaseOrderService orders;
    JdbcClient jdbc;

    @BeforeEach
    void wireBase(@Autowired SupplierService supplierService, @Autowired ItemService itemService,
                  @Autowired PurchaseOrderService orderService, @Autowired JdbcClient jdbcClient) {
        this.suppliers = supplierService;
        this.items = itemService;
        this.orders = orderService;
        this.jdbc = jdbcClient;
        TestActors.reset();
    }

    @AfterEach
    void resetActor() {
        TestActors.reset();
    }

    static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    Supplier approvedSupplier(String currency) {
        TestActors.as(TestActors.BUYER);
        Supplier s = suppliers.create(new SupplierCommand(unique("SUP-"), "Accra Pack Foods Ltd", "GH", "Accra, Ghana",
                currency, "Net 30 · 30% deposit", 35, List.of("FSSC 22000", "Ghana FDA"),
                List.of(new SupplierContact("Efua Mensah", "Sales", "efua@accrapack.example", "+233201234567"))));
        Supplier approved = suppliers.changeStatus(s.id(), SupplierStatus.APPROVED, null, null);
        TestActors.reset();
        return approved;
    }

    RetailItem item(Supplier supplier, String name, String atin, long supplierCostMinor) {
        TestActors.as(TestActors.BUYER);
        RetailItem i = items.create(new ItemCommand(name, "Groceries", atin, supplier.id(),
                List.of(new SupplierItem(supplier.id(), unique("ACC-"), 24, 0, supplierCostMinor,
                        supplier.currency())), null, "0910.91.0000", new BigDecimal("3.2"), 548,
                StorageType.AMBIENT, 150, "6 oz jar", 6, 24, 200, 192, 799));
        TestActors.reset();
        return i;
    }

    /** A PO in status SENT (created by the buyer, approved by the lead). */
    PurchaseOrder sentPo(Supplier supplier, List<PoLineInput> lines) {
        TestActors.as(TestActors.BUYER);
        PurchaseOrder po = orders.create(new CreatePoCommand(supplier.id(), "HOU-FC", "FOB Tema", TODAY,
                TODAY.plusDays(40), supplier.currency().equals("USD") ? null : new BigDecimal("15.42"), lines));
        po = orders.submit(po.id(), po.version());
        TestActors.as(TestActors.FINANCE);
        po = orders.approve(po.id(), "ok", po.version());
        TestActors.as(TestActors.BUYER);
        po = orders.send(po.id(), po.version());
        TestActors.reset();
        return po;
    }

    List<String> outboxTypes(UUID aggregateId) {
        return jdbc.sql("select type from platform_outbox where aggregate_id = :id order by occurred_at, type")
                .param("id", aggregateId.toString()).query(String.class).list();
    }

    static void as(Actor actor) {
        TestActors.as(actor);
    }
}
