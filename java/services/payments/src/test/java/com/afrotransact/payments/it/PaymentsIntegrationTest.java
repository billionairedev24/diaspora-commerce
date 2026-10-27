package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.afrotransact.payments.application.WebhookService;
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
 * One PostgreSQL container, one in-JVM WireMock standing in for api.stripe.com and one Spring context shared by every
 * integration test. Tests use unique order / org ids; jobs are off and called directly; the clock is injectable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.payments.jobs.enabled=false",
    "at.payments.zone=UTC",
    "at.payments.stripe.secret-key=sk_test_integration",
    "at.payments.stripe.webhook-secret=" + PaymentsIntegrationTest.WEBHOOK_SECRET,
    "at.payments.stripe.max-network-retries=0",
    "at.payments.stripe.multicapture=true",
    "spring.datasource.hikari.maximum-pool-size=30"
})
@ActiveProfiles("local")
@Import(TestSupport.class)
abstract class PaymentsIntegrationTest {

    static final String WEBHOOK_SECRET = "whsec_integration_test_secret";
    static final String RETAIL = "org_afrotransact_retail";
    /** {@code AT_IT_POSTGRES_URL=jdbc:postgresql://…} runs the tests on that PostgreSQL instead of a container. */
    static final String EXTERNAL_DB = System.getenv("AT_IT_POSTGRES_URL");
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();
    static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static final StripeStubs STRIPE = new StripeStubs(WIREMOCK);
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static {
        if (EXTERNAL_DB == null) {
            POSTGRES.start();
        }
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> EXTERNAL_DB != null ? EXTERNAL_DB : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_USER", "postgres") : POSTGRES.getUsername());
        registry.add("spring.datasource.password", () -> EXTERNAL_DB != null
                ? System.getenv().getOrDefault("AT_IT_POSTGRES_PASSWORD", "") : POSTGRES.getPassword());
        registry.add("at.payments.stripe.api-base", WIREMOCK::baseUrl);
        // The seller service's billing API (plan fallback) is WireMock too: unstubbed organisations answer 404 = no
        // plan, so they pay category rates.
        registry.add("at.payments.seller.base-url", WIREMOCK::baseUrl);
    }

    JdbcClient jdbc;
    WebhookService webhooks;
    int port;

    @BeforeEach
    void wireBase(@Autowired JdbcClient jdbcClient, @Autowired WebhookService webhookService,
                  @LocalServerPort int serverPort) {
        this.jdbc = jdbcClient;
        this.webhooks = webhookService;
        this.port = serverPort;
    }

    @AfterEach
    void resetActorAndClock() {
        TestSupport.reset();
    }

    static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ HTTP

    HttpResponse<String> send(String method, String path, Object body, String idempotencyKey) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json");
            if (idempotencyKey != null) {
                b.header("Idempotency-Key", idempotencyKey);
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

    // ------------------------------------------------------------------ payments

    static Map<String, Object> line(String lineId, String org, String category, String fulfilment, int qty,
                                    long items, long shipping, long tax) {
        return Map.of("lineId", lineId, "sellerOrg", org, "category", category, "fulfilment", fulfilment,
                "quantity", qty, "itemsMinor", items, "shippingMinor", shipping, "taxMinor", tax,
                "taxJurisdiction", "US-TX-48201");
    }

    /** Creates the payment through the API (as the orders service) and authorises it (Stripe requires_capture). */
    JsonNode createAuthorisedPayment(String orderId, List<Map<String, Object>> lines) {
        long amount = lines.stream().mapToLong(l -> (long) l.get("itemsMinor") + (long) l.get("shippingMinor")
                + (long) l.get("taxMinor")).sum();
        String pi = "pi_" + orderId;
        STRIPE.createIntent(orderId, pi, amount);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode created = ok(send("POST", "/v1/payments", Map.of("orderId", orderId, "customerId", "cus_shopper",
                "amountMinor", amount, "currency", "USD", "lines", lines), "create-" + orderId), 201);
        STRIPE.retrieveIntent(pi, amount, "requires_capture");
        JsonNode refreshed = ok(send("GET", "/v1/payments/" + created.path("id").asString() + "?refresh=true", null,
                null));
        assertThat(refreshed.path("status").asString()).isEqualTo("AUTHORIZED");
        return refreshed;
    }

    JsonNode capture(String paymentId, String orderId, String shipmentId, List<String> lineIds, long amount,
                     long fee, boolean last) {
        String pi = "pi_" + orderId;
        String charge = "ch_" + orderId;
        STRIPE.capture(pi, charge, amount, last ? "succeeded" : "requires_capture");
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        return ok(send("POST", "/v1/payments/" + paymentId + "/capture", Map.of("shipmentId", shipmentId,
                "lineIds", lineIds, "amountMinor", amount, "finalCapture", last), "cap-" + orderId + "-" + shipmentId), 201);
    }

    // ------------------------------------------------------------------ ledger assertions

    void assertEveryJournalBalances() {
        long unbalanced = jdbc.sql("""
                select count(*) from (
                  select journal_id, currency from journal_line group by journal_id, currency
                  having sum(case side when 'D' then amount_minor else -amount_minor end) <> 0) x
                """).query(Long.class).single();
        assertThat(unbalanced).isZero();
    }

    /** Normal-side balance of an account for one order (credit-normal accounts positive when credited). */
    long balance(String orderId, String code, String key) {
        return jdbc.sql("""
                select coalesce(sum(case side when 'C' then amount_minor else -amount_minor end), 0)
                from journal_line where order_id = :o and account_code = :c and account_key = :k
                """).param("o", orderId).param("c", code).param("k", key).query(Long.class).single();
    }

    long outbox(String type, String aggregateId) {
        return jdbc.sql("select count(*) from platform_outbox where type = :t and aggregate_id = :a")
                .param("t", type).param("a", aggregateId).query(Long.class).single();
    }

    // ------------------------------------------------------------------ webhooks

    String event(String eventId, String type, String objectJson) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"type\":\"" + type + "\",\"created\":"
                + TestSupport.CLOCK.instant().getEpochSecond() + ",\"livemode\":false,\"pending_webhooks\":1,"
                + "\"data\":{\"object\":" + objectJson + "}}";
    }

    static String signature(String payload, long timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    HttpResponse<String> deliver(String payload, String signatureHeader) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                    + "/v1/webhooks/stripe")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            if (signatureHeader != null) {
                b.header("Stripe-Signature", signatureHeader);
            }
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Signs with the endpoint secret, delivers, and processes the inbox. */
    JsonNode webhook(String payload) {
        JsonNode ack = ok(deliver(payload, signature(payload, TestSupport.CLOCK.instant().getEpochSecond(),
                WEBHOOK_SECRET)), 200);
        webhooks.processPending(100);
        return ack;
    }
}
