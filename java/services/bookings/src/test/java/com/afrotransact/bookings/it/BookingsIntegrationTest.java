package com.afrotransact.bookings.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.platform.test.PostgresTestContainer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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
 * One PostgreSQL container, in-JVM WireMocks standing in for payments-service (with identity's token endpoint) and
 * geo-service, and one Spring context shared by every integration test. Kafka and jobs are off (the payment-event
 * ingest, the maintenance and the balance-charge use cases are called directly); each test uses its own provider
 * organisation. The card authorisation window is 7 days, so bookings on {@link #day()} keep their authorisation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "spring.kafka.listener.auto-startup=false",
    "at.bookings.jobs.enabled=false",
    "at.bookings.payments.client-secret=it-secret",
    "at.bookings.balance.authorisation-window=7d",
    "spring.datasource.hikari.maximum-pool-size=20"
})
@ActiveProfiles("local")
@Import(TestSupport.class)
abstract class BookingsIntegrationTest {

    static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();
    static final WireMockServer PAYMENTS = new WireMockServer(WireMockConfiguration.options().dynamicPort()
            .globalTemplating(true));
    static final WireMockServer GEO = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static {
        POSTGRES.start();
        PAYMENTS.start();
        GEO.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("at.bookings.payments.base-url", PAYMENTS::baseUrl);
        registry.add("at.bookings.payments.token-uri", () -> PAYMENTS.baseUrl() + "/oauth2/token");
        registry.add("at.bookings.geo.base-url", GEO::baseUrl);
    }

    JdbcClient jdbc;
    int port;

    @BeforeEach
    void wireBase(@Autowired JdbcClient jdbcClient, @LocalServerPort int serverPort) {
        this.jdbc = jdbcClient;
        this.port = serverPort;
        stubPayments();
        GEO.resetAll();
    }

    @AfterEach
    void resetActorAndClock() {
        TestSupport.reset();
    }

    /** payments-service and identity's token endpoint as WireMock stubs (fresh for each test). */
    static void stubPayments() {
        PAYMENTS.resetAll();
        PAYMENTS.stubFor(WireMock.post(WireMock.urlEqualTo("/oauth2/token"))
                .willReturn(WireMock.okJson("{\"access_token\":\"tok-bookings\",\"token_type\":\"Bearer\","
                        + "\"expires_in\":300}")));
        PAYMENTS.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/payments"))
                .willReturn(WireMock.aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"{{randomValue type='UUID'}}\","
                                + "\"orderId\":\"{{jsonPath request.body '$.orderId'}}\","
                                + "\"status\":\"REQUIRES_PAYMENT_METHOD\","
                                + "\"clientSecret\":\"pi_{{jsonPath request.body '$.orderId'}}_secret\"}")));
        PAYMENTS.stubFor(WireMock.get(WireMock.urlPathMatching("/v1/payments/[^/]+"))
                .willReturn(WireMock.okJson("{\"id\":\"{{request.pathSegments.[2]}}\",\"status\":\"AUTHORIZED\"}")));
        PAYMENTS.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/payments/[^/]+/(capture|cancel|refunds)"))
                .willReturn(WireMock.aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{}")));
        PAYMENTS.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/payments/[^/]+/release"))
                .willReturn(WireMock.okJson("{\"id\":\"{{request.pathSegments.[2]}}\",\"status\":\"CAPTURED\","
                        + "\"releasedMinor\":9000}")));
        PAYMENTS.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/settlements/[^/]+/delivered"))
                .willReturn(WireMock.okJson("{\"kind\":\"SERVICE\"}")));
    }

    static String id(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
    }

    /** Organisation id in identity's format: {@code org_} + 20 lowercase letters / digits. */
    static String orgId() {
        return "org_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    /** Unique part of an org id, used in the provider's display name so searches find only this provider. */
    static String tag(String org) {
        return org.substring(4, 14);
    }

    /** A date five days ahead in the provider's zone. */
    static LocalDate day() {
        return LocalDate.ofInstant(TestSupport.CLOCK.instant(), CHICAGO).plusDays(5);
    }

    // ------------------------------------------------------------------ HTTP

    HttpResponse<String> send(String method, String path, Object body, String idempotencyKey) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json");
            if (idempotencyKey != null) {
                b.header("Idempotency-Key", idempotencyKey);
            }
            String json = body == null ? "" : JSON.writeValueAsString(body);
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

    // ------------------------------------------------------------------ provider setup

    /** A provider in Chicago, open 09:00–17:00 every day, with an owner who takes bookings and one service. */
    Setup provider(boolean instant) {
        String org = orgId();
        TestSupport.as(TestSupport.owner(org));
        ok(send("PUT", "/v1/providers/" + org + "/settings", Map.of("displayName", "Aminata's Braids " + tag(org),
                "timeZone", "America/Chicago", "currency", "USD", "location", "Houston, TX",
                "rules", Map.of("leadMinutes", 60, "horizonDays", 30, "bufferMinutes", 15, "stepMinutes", 30,
                        "maxPerDay", 6, "instantBooking", instant, "autoAcceptReschedule", false)), null));
        Map<String, Object> hours = Map.of("days", Map.of(
                "MONDAY", List.of(range("09:00", "17:00")), "TUESDAY", List.of(range("09:00", "17:00")),
                "WEDNESDAY", List.of(range("09:00", "17:00")), "THURSDAY", List.of(range("09:00", "17:00")),
                "FRIDAY", List.of(range("09:00", "17:00")), "SATURDAY", List.of(range("09:00", "17:00")),
                "SUNDAY", List.of(range("09:00", "17:00"))));
        JsonNode owner = ok(send("POST", "/v1/providers/" + org + "/team", Map.of("userId", "owner-" + org,
                "displayName", "Aminata", "role", "OWNER", "hours", hours), null), 201);
        JsonNode service = ok(send("POST", "/v1/providers/" + org + "/services", Map.of("name", "Cornrows · 8–10 rows",
                "description", "Neat rows", "category", "hair & beauty", "durationMinutes", 60,
                "priceMinor", 12_000, "depositMinor", 3_000, "modes", List.of("IN_PERSON", "AT_HOME"),
                "addOns", List.of(Map.of("id", "wash", "name", "Wash & condition", "priceMinor", 1_500,
                        "extraMinutes", 30))), null), 201);
        TestSupport.reset();
        return new Setup(org, UUID.fromString(owner.path("id").asString()),
                UUID.fromString(service.path("id").asString()), hours);
    }

    static Map<String, String> range(String start, String end) {
        return Map.of("start", start, "end", end);
    }

    /** First free start time of the service on {@link #day()}. */
    String firstSlot(UUID serviceId) {
        return firstSlot(serviceId, day());
    }

    /** First free start time of the service on {@code date}. */
    String firstSlot(UUID serviceId, LocalDate date) {
        JsonNode slots = ok(send("GET", "/v1/public/services/" + serviceId + "/slots?date=" + date, null, null));
        assertThat(slots.size()).isGreaterThan(0);
        return slots.get(0).path("start").asString();
    }

    Map<String, Object> bookingBody(UUID serviceId, String startAt) {
        return Map.of("serviceId", serviceId, "startAt", startAt, "mode", "IN_PERSON",
                "customer", Map.of("name", "Adaeze Nwosu", "email", "adaeze@example.com", "phone", "+17135550142"));
    }

    /** Books as {@code customer} and returns the booking JSON. */
    JsonNode book(String customer, UUID serviceId, String startAt) {
        TestSupport.as(TestSupport.customer(customer));
        JsonNode created = ok(send("POST", "/v1/bookings", bookingBody(serviceId, startAt), id("key")), 201);
        return created.path("booking");
    }

    List<String> outboxTypes(String aggregateId) {
        return jdbc.sql("select type from platform_outbox where aggregate_id = :id order by occurred_at")
                .param("id", aggregateId).query(String.class).list();
    }

    /** Provider created by {@link #provider}. */
    record Setup(String org, UUID ownerId, UUID serviceId, Map<String, Object> hours) {
    }
}
