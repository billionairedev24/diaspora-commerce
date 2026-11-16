package com.afrotransact.mcp.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.afrotransact.mcp.port.Downstream;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The payment split is orders-service's endpoint; payments-service serves the payout reads. */
class HttpPaymentsApiTest {

    private HttpServer payments;
    private HttpServer orders;
    private final List<String> paymentsRequests = new CopyOnWriteArrayList<>();
    private final List<String> ordersRequests = new CopyOnWriteArrayList<>();

    private static HttpServer serve(List<String> seen) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(exchange.getRequestURI().toString());
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    @BeforeEach
    void start() throws IOException {
        payments = serve(paymentsRequests);
        orders = serve(ordersRequests);
    }

    @AfterEach
    void stop() {
        payments.stop(0);
        orders.stop(0);
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void thePaymentSplitIsReadFromOrdersAndThePayoutsFromPayments() {
        HttpPaymentsApi api = new HttpPaymentsApi(RestClient.builder(), url(payments), url(orders), Duration.ofSeconds(2));

        Downstream split = api.orderSplit("AT-1", "tok");
        Downstream balance = api.payoutBalance("org-1", "tok");

        assertThat(split.ok()).isTrue();
        assertThat(balance.ok()).isTrue();
        assertThat(ordersRequests).containsExactly("/v1/orders/AT-1/payment-split");
        assertThat(paymentsRequests).containsExactly("/v1/payouts/balance?orgId=org-1");
    }
}
