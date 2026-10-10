package com.afrotransact.orders.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import com.afrotransact.orders.OrdersApplication;
import com.afrotransact.orders.application.CarrierEventService;
import com.afrotransact.orders.application.OrderPlacementService;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.OrderTotals;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * S219: two orders replicas (two application contexts) share PostgreSQL and Valkey (local valkey/valkey:8.1-alpine
 * image). A staff client streaming from replica B sees, within 2 seconds, a transition applied on replica A by a
 * carrier.pickup.ready notice.
 */
class LiveFeedFanoutIT {

    private static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();
    private static final GenericContainer<?> VALKEY = new GenericContainer<>(
            DockerImageName.parse("valkey/valkey:8.1-alpine")).withExposedPorts(6379);
    private static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static ConfigurableApplicationContext replicaA;
    private static ConfigurableApplicationContext replicaB;

    @BeforeAll
    static void startReplicas() {
        POSTGRES.start();
        VALKEY.start();
        WIREMOCK.start();
        new Upstreams(WIREMOCK).common();
        replicaA = replica();
        replicaB = replica();
    }

    @AfterAll
    static void stopReplicas() {
        if (replicaB != null) {
            replicaB.close();
        }
        if (replicaA != null) {
            replicaA.close();
        }
        WIREMOCK.stop();
        VALKEY.stop();
    }

    private static ConfigurableApplicationContext replica() {
        String upstream = WIREMOCK.baseUrl();
        return new SpringApplicationBuilder(OrdersApplication.class, TestSupport.class).profiles("local").run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.url=redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379),
                "--at.orders.live-feed.fanout=valkey",
                "--at.events.relay-enabled=false",
                "--spring.kafka.bootstrap-servers=localhost:1",
                "--at.orders.consumers.enabled=false",
                "--at.orders.jobs.enabled=false",
                "--at.orders.oauth.client-secret=test-orders-secret",
                "--at.orders.oauth.issuer-url=" + upstream,
                "--at.orders.upstreams.cart-url=" + upstream,
                "--at.orders.upstreams.identity-url=" + upstream,
                "--at.orders.upstreams.inventory-url=" + upstream,
                "--at.orders.upstreams.payments-url=" + upstream,
                "--at.orders.upstreams.promise-url=" + upstream,
                "--at.orders.upstreams.content-url=" + upstream,
                "--at.orders.upstreams.catalog-url=" + upstream,
                "--at.orders.upstreams.offers-url=" + upstream,
                "--at.orders.upstreams.tax-returns-url=" + upstream,
                "--spring.temporal.connection.target=",
                "--spring.temporal.test-server.enabled=true",
                "--spring.temporal.start-workers=false");
    }

    private static int port(ConfigurableApplicationContext ctx) {
        return ((WebServerApplicationContext) ctx).getWebServer().getPort();
    }

    @Test
    void everyReplicaStreamsTransitionsAppliedOnAnother() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        HttpRequest stream = HttpRequest.newBuilder(URI.create("http://localhost:" + port(replicaB)
                + "/v1/shipments/stream")).header("Accept", "text/event-stream").GET().build();
        CompletableFuture<HttpResponse<Stream<String>>> response = HTTP.sendAsync(stream,
                HttpResponse.BodyHandlers.ofLines());
        HttpResponse<Stream<String>> sse = response.get(20, TimeUnit.SECONDS);
        assertThat(sse.statusCode()).isEqualTo(200);
        Thread reader = Thread.ofVirtual().start(() -> sse.body().forEach(lines::add));
        await(() -> lines.stream().anyMatch(l -> l.contains("event:ready")), Duration.ofSeconds(10));

        String customer = "cust-" + UUID.randomUUID().toString().substring(0, 8);
        Order order = packedPickupOrder(customer);
        String shipment = order.shipments().getFirst().id();

        Instant applied = Instant.now();
        assertThat(replicaA.getBean(CarrierEventService.class).pickupReady(UUID.randomUUID(),
                new CarrierEventService.PickupReady("PICKUP-1", "Houston counter", order.number(), customer,
                        Instant.now()))).isEqualTo(CarrierEventService.Result.APPLIED);
        await(() -> lines.stream().anyMatch(l -> l.contains(shipment) && l.contains("\"to\":\"ready\"")),
                Duration.ofSeconds(2));
        assertThat(Duration.between(applied, Instant.now())).isLessThan(Duration.ofSeconds(2));
        // The earlier transitions applied on replica A reached replica B's stream as well.
        assertThat(lines.stream().filter(l -> l.contains(shipment) && l.contains("\"to\":\"packed\""))).isNotEmpty();
        reader.interrupt();
        sse.body().close();
    }

    /** A placed Fulfilled-by-AfroTransact pickup order on replica A, confirmed and packed through A's API. */
    private static Order packedPickupOrder(String customer) throws Exception {
        String orderId = UUID.randomUUID().toString();
        String shipmentId = UUID.randomUUID().toString();
        OrderLine line = new OrderLine(UUID.randomUUID().toString(), 1, "ATIN-FAN", "P", "Jollof spice", null, 2, 1500,
                248, 0, "groceries", false, "US-TX");
        Shipment shipment = new Shipment(shipmentId, orderId, 1, "org-fanout", "Seller 1",
                FulfilmentType.FBA_AFROTRANSACT, "HOU-FC", DeliveryMethod.PICKUP, "standard", "PICKUP-1",
                DeliveryPreferences.defaults(), false, false, null, null, ShipmentStatus.PENDING_PAYMENT, 599, null,
                null, LocalDate.now(), LocalDate.now().plusDays(3), null, null, null, null, Instant.now(),
                List.of(line), 0);
        Order order = new Order(orderId, "AT-2026-" + UUID.randomUUID().toString().substring(0, 6)
                .toUpperCase(Locale.ROOT), customer, "USD", OrderStatus.PENDING_PAYMENT,
                OrderTotals.ofShipments(List.of(shipment)), new ShipTo("addr-1", "Ada Obi", "+17135550142",
                "1 Main St", null, "Houston", "TX", "77002", "US", "Gate 4"), UUID.randomUUID().toString(),
                "checkout-" + orderId, null, null, Instant.now(), List.of(shipment), 0);
        OrderPlacementService placement = replicaA.getBean(OrderPlacementService.class);
        placement.persistPending(order, null);
        Order placed = placement.place(orderId);
        for (String to : List.of("confirmed", "packed")) {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port(replicaA)
                    + "/v1/shipments/" + shipmentId + "/transitions")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"to\":\"" + to + "\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        }
        return placed;
    }

    private static void await(BooleanSupplier condition, Duration within) {
        long deadline = System.nanoTime() + within.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("Not seen within " + within);
    }
}
