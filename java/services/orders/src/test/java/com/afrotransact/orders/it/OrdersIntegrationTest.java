package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import com.afrotransact.orders.application.OrderPlacementService;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.HandoverPolicy;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.OrderTotals;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.port.HandoverCodeVault;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One PostgreSQL container, one in-JVM WireMock standing in for every upstream (identity, cart, inventory, payments,
 * promise, content, catalog), Temporal's in-JVM test server (temporal-spring-boot-starter) running the real checkout
 * worker, and one Spring context shared by every integration test. Kafka consumers and jobs are off; tests call the
 * consumers' services and the follow-up runner directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.orders.consumers.enabled=false",
    "at.orders.jobs.enabled=false",
    "at.orders.checkout-response-timeout=15s",
    "at.orders.oauth.client-secret=test-orders-secret",
    "at.orders.upstreams.timeout=3s",
    "spring.temporal.connection.target=",
    "spring.temporal.test-server.enabled=true",
    "spring.datasource.hikari.maximum-pool-size=20"
})
@ActiveProfiles("local")
@Import(TestSupport.class)
abstract class OrdersIntegrationTest {

    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();
    static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static final Upstreams UPSTREAMS = new Upstreams(WIREMOCK);
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static {
        POSTGRES.start();
        WIREMOCK.start();
        UPSTREAMS.common();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        for (String upstream : List.of("cart", "identity", "inventory", "payments", "promise", "content", "catalog",
                "offers", "tax-returns")) {
            registry.add("at.orders.upstreams." + upstream + "-url", WIREMOCK::baseUrl);
        }
        registry.add("at.orders.oauth.issuer-url", WIREMOCK::baseUrl);
    }

    JdbcClient jdbc;
    OrderPlacementService placement;
    HandoverPolicy handover;
    HandoverCodeVault vault;
    int port;

    @BeforeEach
    void wireBase(@Autowired JdbcClient jdbcClient, @Autowired OrderPlacementService orderPlacement,
                  @Autowired HandoverPolicy handoverPolicy, @Autowired HandoverCodeVault codeVault,
                  @LocalServerPort int serverPort) {
        this.jdbc = jdbcClient;
        this.placement = orderPlacement;
        this.handover = handoverPolicy;
        this.vault = codeVault;
        this.port = serverPort;
    }

    @AfterEach
    void resetActor() {
        TestSupport.reset();
    }

    static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ HTTP

    HttpResponse<String> send(String method, String path, Object body, String... headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json");
            for (int i = 0; i + 1 < headers.length; i += 2) {
                b.header(headers[i], headers[i + 1]);
            }
            String json = body == null ? "" : body instanceof String s ? s : JSON.writeValueAsString(body);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    JsonNode ok(HttpResponse<String> response, int... statuses) {
        assertThat(statuses.length == 0 ? List.of(200, 201).contains(response.statusCode())
                : Arrays.stream(statuses).anyMatch(s -> s == response.statusCode()))
                .as("HTTP %s: %s", response.statusCode(), response.body()).isTrue();
        return response.body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }

    JsonNode problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode p = JSON.readTree(response.body());
        assertThat(p.path("code").asString()).isEqualTo(code);
        return p;
    }

    JsonNode transition(String shipmentId, String to, String reason, String key) {
        HttpResponse<String> r = send("POST", "/v1/shipments/" + shipmentId + "/transitions",
                reason == null ? "{\"to\":\"" + to + "\"}" : "{\"to\":\"" + to + "\",\"reason\":\"" + reason + "\"}",
                key == null ? new String[0] : new String[] {"Idempotency-Key", key});
        return r.statusCode() < 300 ? JSON.readTree(r.body()) : problemNode(r);
    }

    HttpResponse<String> transitionRaw(String shipmentId, String to) {
        return send("POST", "/v1/shipments/" + shipmentId + "/transitions", "{\"to\":\"" + to + "\"}");
    }

    private static JsonNode problemNode(HttpResponse<String> r) {
        throw new AssertionError("HTTP " + r.statusCode() + ": " + r.body());
    }

    static void eventually(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    // ------------------------------------------------------------------ fixtures

    /** One shipment to create in {@link #placedOrder}. */
    record Spec(String sellerOrg, FulfilmentType type, DeliveryMethod method, boolean perishable,
                DeliveryPreferences preferences, long unitPrice, int qty) {
    }

    static Spec spec(String sellerOrg, FulfilmentType type, DeliveryMethod method) {
        return new Spec(sellerOrg, type, method, false, DeliveryPreferences.defaults(), 1500, 2);
    }

    /** A placed order (payment authorised) created through the placement use case, bypassing the saga. */
    Order placedOrder(String customerId, String paymentId, Spec... specs) {
        String orderId = UUID.randomUUID().toString();
        List<Shipment> shipments = new ArrayList<>();
        int seq = 0;
        int lineNo = 0;
        for (Spec s : specs) {
            seq++;
            lineNo++;
            String shipmentId = UUID.randomUUID().toString();
            OrderLine line = new OrderLine(UUID.randomUUID().toString(), lineNo, id("ATIN"), "P", "Jollof spice", null,
                    s.qty(), s.unitPrice(), 248, 0, "groceries", s.perishable(), "US-TX");
            boolean code = handover.required(s.perishable(), s.method(), s.preferences());
            String plain = code ? "4821" : null;
            shipments.add(new Shipment(shipmentId, orderId, seq, s.sellerOrg(), "Seller " + seq, s.type(),
                    s.type().inventoryTracked() ? "HOU-FC" : null, s.method(), "standard",
                    s.method() == DeliveryMethod.PICKUP ? "PICKUP-1" : null, s.preferences(), s.perishable(), code,
                    code ? handover.hash(shipmentId, plain) : null, code ? vault.seal(shipmentId, plain) : null,
                    ShipmentStatus.PENDING_PAYMENT, 599, null, null, LocalDate.now(), LocalDate.now().plusDays(3),
                    null, null, null, null, Instant.now(), List.of(line), 0));
        }
        Order order = new Order(orderId, "AT-2026-" + UUID.randomUUID().toString().substring(0, 6)
                .toUpperCase(Locale.ROOT), customerId, "USD", OrderStatus.PENDING_PAYMENT,
                OrderTotals.ofShipments(shipments), new ShipTo("addr-1", "Ada Obi", "+17135550142", "1 Main St", null,
                "Houston", "TX", "77002", "US", "Gate 4"), paymentId, "checkout-" + orderId, null, null,
                Instant.now(), shipments, 0);
        placement.persistPending(order, null);
        return placement.place(orderId);
    }

    String status(String shipmentId) {
        return jdbc.sql("select status from shipment where id = :id").param("id", UUID.fromString(shipmentId))
                .query(String.class).single();
    }

    long outbox(String type, String aggregateId) {
        return jdbc.sql("select count(*) from platform_outbox where type = :t and aggregate_id = :a")
                .param("t", type).param("a", aggregateId).query(Long.class).single();
    }

    long history(String shipmentId) {
        return jdbc.sql("select count(*) from shipment_status_change where shipment_id = :s")
                .param("s", UUID.fromString(shipmentId)).query(Long.class).single();
    }
}
