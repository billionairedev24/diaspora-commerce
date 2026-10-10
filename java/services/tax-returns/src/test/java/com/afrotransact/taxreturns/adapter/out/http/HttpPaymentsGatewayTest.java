package com.afrotransact.taxreturns.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.returns.ItemCondition;
import com.afrotransact.taxreturns.domain.returns.RefundLineAmount;
import com.afrotransact.taxreturns.port.PaymentsGateway;
import com.afrotransact.taxreturns.port.UpstreamException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** The payments and inventory adapters against a local HTTP server: paths, headers, bodies and error mapping. */
class HttpPaymentsGatewayTest {

    private static final UUID ORDER = UUID.fromString("0b9f0f7e-6c39-4a77-9f2a-1c1f3b8e0a01");
    private static final UUID PAYMENT = UUID.fromString("5d6a3c52-0f3e-4a8b-8b7d-6a7e2f9b1c02");

    private final Map<String, String> requests = new ConcurrentHashMap<>();
    private HttpServer server;
    private HttpPaymentsGateway payments;
    private HttpInventoryGateway inventory;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/payments", this::payments);
        server.createContext("/v1/returns", ex -> {
            record(ex);
            respond(ex, 409, "{\"code\":\"inventory.return-exceeds-shipped\",\"detail\":\"Too many\"}");
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        TaxReturnsProperties props = new TaxReturnsProperties(null, null, new TaxReturnsProperties.Upstreams(
                base + "/oauth2/token", null, "", Duration.ofSeconds(5), base, base, base, base, base, base), null,
                null);
        JsonMapper json = JsonMapper.builder().build();
        ClientCredentialsTokens tokens = new ClientCredentialsTokens(RestClient.builder(), props, Clock.systemUTC());
        payments = new HttpPaymentsGateway(RestClient.builder(), props, tokens, json);
        inventory = new HttpInventoryGateway(RestClient.builder(), props, tokens, json);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void payments(HttpExchange ex) throws IOException {
        record(ex);
        String path = ex.getRequestURI().getPath();
        if ("GET".equals(ex.getRequestMethod()) && ex.getRequestURI().getQuery().contains(ORDER.toString())) {
            respond(ex, 200, """
                    {"id":"%s","orderId":"%s","currency":"USD","lines":[
                      {"lineId":"l1","sellerOrg":"org_a","quantity":2,"itemsMinor":5000,"shippingMinor":799,
                       "taxMinor":413,"captureId":"%s","refundedItemsMinor":2500,"refundedShippingMinor":0,
                       "refundedTaxMinor":207},
                      {"lineId":"l2","sellerOrg":"org_a","quantity":1,"itemsMinor":1000,"shippingMinor":0,
                       "taxMinor":83}]}
                    """.formatted(PAYMENT, ORDER, UUID.randomUUID()));
        } else if ("GET".equals(ex.getRequestMethod())) {
            respond(ex, 404, "{\"code\":\"payments.not-found\"}");
        } else if (path.endsWith("/refunds")) {
            respond(ex, 200, "{\"id\":\"rf_1\",\"status\":\"SUCCEEDED\",\"amountMinor\":5413}");
        } else {
            respond(ex, 500, "{}");
        }
    }

    private void record(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.put(ex.getRequestMethod() + " " + ex.getRequestURI().getPath(), body + "|"
                + ex.getRequestHeaders().getFirst("Idempotency-Key") + "|"
                + ex.getRequestHeaders().getFirst("Authorization"));
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void readsChargedLinesAndCaptureState() {
        PaymentsGateway.ChargedPayment p = payments.paymentForOrder(ORDER).orElseThrow();
        assertThat(p.paymentId()).isEqualTo(PAYMENT);
        assertThat(p.lines()).containsExactly(
                new PaymentsGateway.ChargedLine("l1", "org_a", 2, 5_000, 799, 413, true, 2_500, 0, 207),
                new PaymentsGateway.ChargedLine("l2", "org_a", 1, 1_000, 0, 83, false));
        assertThat(payments.paymentForOrder(UUID.randomUUID())).isEmpty();
    }

    @Test
    void refundsWithIdempotencyKeyAndNoTokenWhenSecretIsBlank() {
        PaymentsGateway.IssuedRefund r = payments.refund(PAYMENT,
                List.of(new RefundLineAmount("l1", 1, 2_500, 0, 207, 0)), "Return RMA-1", "key-1");
        assertThat(r).isEqualTo(new PaymentsGateway.IssuedRefund("rf_1", "SUCCEEDED", 5_413));
        String sent = requests.get("POST /v1/payments/" + PAYMENT + "/refunds");
        assertThat(sent).contains("\"lineId\":\"l1\"", "\"itemsMinor\":2500", "\"taxMinor\":207",
                "\"reason\":\"Return RMA-1\"").endsWith("|key-1|null");
    }

    @Test
    void mapsProblemsToUpstreamExceptions() {
        assertThatThrownBy(() -> inventory.acceptReturn(ORDER.toString(), "AT1", 1, ItemCondition.RESTOCK, "r",
                "RET-FC", "k")).isInstanceOfSatisfying(UpstreamException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(409);
                    assertThat(ex.code()).isEqualTo("inventory.return-exceeds-shipped");
                    assertThat(ex.retryable()).isFalse();
                });
        assertThat(requests.get("POST /v1/returns")).contains("\"condition\":\"RESTOCK\"", "\"qty\":1",
                "\"receivingNodeId\":\"RET-FC\"");
        server.stop(0);
        assertThatThrownBy(() -> payments.paymentForOrder(ORDER)).isInstanceOfSatisfying(UpstreamException.class,
                ex -> assertThat(ex.retryable()).isTrue());
    }
}
