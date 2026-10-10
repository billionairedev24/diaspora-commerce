package com.afrotransact.offers.adapter.out.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.offers.port.PickupLocationClient.PickupLocation;
import com.afrotransact.offers.port.PickupLocationClient.SellerUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The seller-service adapter against a loopback stub (no external network). */
class HttpPickupLocationClientTest {

    private static final String LOCATION = """
            {"id":"7d1f2c3e-0000-4000-8000-000000000001","orgId":"org_lagos","city":"Houston","state":"TX",
             "country":"US","postalCode":"77002","position":{"lat":29.7589,"lng":-95.3677},
             "afrotransactPickup":{"enabled":true,"hours":[],"zoneName":"Houston core"}}
            """;

    private HttpServer server;
    private HttpPickupLocationClient client;
    private final AtomicReference<String> auth = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String path = exchange.getRequestURI().getPath();
            int status = path.endsWith("/7d1f2c3e-0000-4000-8000-000000000001") ? 200
                    : path.endsWith("/broken") ? 500 : 404;
            byte[] body = (status == 200 ? LOCATION : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        client = new HttpPickupLocationClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress()
                .getPort(), Duration.ofSeconds(2), () -> "svc-offers-token");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsTheLocationWithTheServiceToken() {
        PickupLocation l = client.find("7d1f2c3e-0000-4000-8000-000000000001").orElseThrow();
        assertThat(l.orgId()).isEqualTo("org_lagos");
        assertThat(l.pickupEnabled()).isTrue();
        assertThat(l.city()).isEqualTo("Houston");
        assertThat(l.region()).isEqualTo("TX");
        assertThat(auth.get()).isEqualTo("Bearer svc-offers-token");
    }

    @Test
    void unknownIsEmptyAndOutagesAreUnavailable() {
        assertThat(client.find("missing")).isEmpty();
        assertThatThrownBy(() -> client.find("broken")).isInstanceOf(SellerUnavailableException.class);
    }
}
