package com.afrotransact.fulfilment.it;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import com.afrotransact.fulfilment.application.CentreService;
import com.afrotransact.fulfilment.application.PlacedOrder;
import com.afrotransact.fulfilment.application.ShipmentIntakeService;
import com.afrotransact.fulfilment.domain.FulfilmentCentre;
import com.afrotransact.fulfilment.domain.GeoPoint;
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
 * One PostgreSQL container, one in-JVM WireMock standing in for identity, orders, inventory, geo and delivery-runs, and
 * one Spring context shared by every integration test. Kafka consumers and the relay job are off; tests call the
 * consumers' services and the relay directly. Set {@code AT_IT_POSTGRES_URL} (jdbc:postgresql://…, a PostGIS-enabled
 * server; user and password from {@code AT_IT_POSTGRES_USER} / {@code AT_IT_POSTGRES_PASSWORD}) to run without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.consumers.enabled=false",
    "at.fulfilment.jobs.enabled=false",
    "at.fulfilment.oauth.client-secret=test-fulfilment-secret",
    "at.fulfilment.upstreams.timeout=3s"
})
@ActiveProfiles("local")
@Import(TestSupport.class)
abstract class FulfilmentIntegrationTest {

    static final String NODE = "HOU-FC1";
    static final GeoPoint DEPOT = new GeoPoint(29.70, -95.45);
    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();
    static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static final Upstreams UPSTREAMS = new Upstreams(WIREMOCK);
    static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static {
        if (EXTERNAL_DB == null) {
            POSTGRES.start();
        }
        WIREMOCK.start();
        UPSTREAMS.common();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_DB != null ? EXTERNAL_DB : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "") : POSTGRES.getPassword());
        for (String upstream : List.of("orders", "inventory", "geo", "delivery-runs")) {
            registry.add("at.fulfilment.upstreams." + upstream + "-url", WIREMOCK::baseUrl);
        }
        registry.add("at.fulfilment.oauth.issuer-url", WIREMOCK::baseUrl);
    }

    JdbcClient jdbc;
    ShipmentIntakeService intake;
    int port;

    @BeforeEach
    void wireBase(@Autowired JdbcClient jdbcClient, @Autowired ShipmentIntakeService intakeService,
                  @Autowired CentreService centres, @LocalServerPort int serverPort) {
        this.jdbc = jdbcClient;
        this.intake = intakeService;
        this.port = serverPort;
        TestSupport.reset();
        centres.save(new FulfilmentCentre(NODE, "Houston FC", "FULFILMENT_CENTRE", "America/Chicago",
                "1 Depot Way", "Houston", "TX", "77036", "US", DEPOT, true, true));
    }

    @AfterEach
    void resetActor() {
        TestSupport.reset();
    }

    static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ orders events

    /** One placed order with the given shipments (FBA = from the FC, SELLER = seller pickup). */
    PlacedOrder order(String orderId, String zip, PlacedOrder.PlacedShipment... shipments) {
        return new PlacedOrder(orderId, "AT-2026-" + orderId.substring(orderId.length() - 6), "cust-1", "Houston",
                "TX", zip, "US", null, null, List.of(shipments));
    }

    static PlacedOrder.PlacedShipment shipment(String shipmentId, String type, String method, boolean handover,
                                               Boolean perishable, String atin) {
        return new PlacedOrder.PlacedShipment(shipmentId, "org-mama-tee", "Mama Tee's Pantry", type, method,
                LocalDate.of(2026, 10, 11), handover, perishable, null,
                List.of(new PlacedOrder.PlacedLine(UUID.randomUUID().toString(), atin, "Item " + atin, 1)));
    }

    // ------------------------------------------------------------------ driver events

    /** Builds a driver event body for POST /v1/driver/sync. */
    static Map<String, Object> event(long seq, String type, String routeId, String stopId, Instant at,
                                     Object... payload) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("sequence", seq);
        e.put("type", type);
        e.put("routeId", routeId);
        if (stopId != null) {
            e.put("stopId", stopId);
        }
        e.put("capturedAt", at.toString());
        e.put("lat", 29.7376);
        e.put("lon", -95.5134);
        for (int i = 0; i + 1 < payload.length; i += 2) {
            e.put(String.valueOf(payload[i]), payload[i + 1]);
        }
        return e;
    }

    static Map<String, Object> photo(String routeId, Instant at, double lat, double lon) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("assetId", UUID.randomUUID().toString());
        p.put("source", "LIVE_CAMERA");
        p.put("capturedAt", at.toString());
        p.put("lat", lat);
        p.put("lon", lon);
        p.put("accuracyM", 6);
        p.put("stampRouteId", routeId);
        p.put("sha256", "9f2c");
        return p;
    }

    static Map<String, Object> inspection(String routeId, Instant at, String vehicle, int odo, Double coldBox,
                                          String... failing) {
        Map<String, Object> checks = new LinkedHashMap<>();
        for (String item : List.of("TYRES", "BRAKES", "LIGHTS", "MIRRORS", "HORN_WIPERS", "NO_LEAKS", "CARGO_AREA",
                "COLD_BOX", "SAFETY_KIT", "PHONE_MOUNT")) {
            checks.put(item, Arrays.asList(failing).contains(item) ? "FAIL" : "PASS");
        }
        Map<String, Object> photos = new LinkedHashMap<>();
        for (String slot : List.of("FRONT", "BACK", "LEFT", "RIGHT")) {
            photos.put(slot, photo(routeId, at, 29.70, -95.45));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("vehicleId", vehicle);
        r.put("checks", checks);
        r.put("odometerMiles", odo);
        r.put("fuelPercent", 75);
        r.put("coldBoxF", coldBox);
        r.put("photos", photos);
        r.put("defectNote", failing.length == 0 ? null : "Failed: " + String.join(", ", failing));
        r.put("certified", true);
        r.put("signatureAssetId", "sig-driver");
        return r;
    }

    static Map<String, Object> scan(String label, boolean manual) {
        return Map.of("label", label, "method", manual ? "MANUAL" : "CAMERA");
    }

    JsonNode sync(String deviceId, List<Map<String, Object>> events, String bearer) {
        return ok(send("POST", "/v1/driver/sync", Map.of("deviceId", deviceId, "events", events),
                "Authorization", "Bearer " + bearer));
    }

    static List<String> outcomes(JsonNode syncResult) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : syncResult.path("results")) {
            out.add(r.path("outcome").asString() + (r.path("code").isMissingNode() || r.path("code").isNull() ? ""
                    : ":" + r.path("code").asString()));
        }
        return out;
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
        assertThat(statuses.length == 0 ? List.of(200, 201, 204).contains(response.statusCode())
                : Arrays.stream(statuses).anyMatch(s -> s == response.statusCode()))
                .as("HTTP %s: %s", response.statusCode(), response.body()).isTrue();
        return response.body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }

    JsonNode problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode p = JSON.readTree(response.body());
        assertThat(p.path("code").asString()).as(response.body()).isEqualTo(code);
        return p;
    }

    /** Opens a server-sent event stream; lines arrive in the returned queue until the context shuts down. */
    BlockingQueue<String> openStream(String path) {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/event-stream").build();
        CompletableFuture<HttpResponse<Stream<String>>> response = HTTP.sendAsync(req,
                HttpResponse.BodyHandlers.ofLines());
        Thread.ofVirtual().start(() -> {
            try {
                response.get(20, TimeUnit.SECONDS).body().forEach(lines::add);
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                lines.add("error:" + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return lines;
    }

    /** Waits for a line starting with {@code prefix}; returns the lines seen up to it. */
    static List<String> await(BlockingQueue<String> lines, String prefix) {
        List<String> seen = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try {
            while (System.nanoTime() < deadline) {
                String line = lines.poll(200, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                seen.add(line);
                if (line.startsWith(prefix)) {
                    return seen;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new AssertionError("No SSE line starting with " + prefix + " in " + seen);
    }

    List<String> outboxTypes(String aggregateId) {
        return jdbc.sql("select type from platform_outbox where aggregate_id = :id order by occurred_at")
                .param("id", aggregateId).query(String.class).list();
    }
}
