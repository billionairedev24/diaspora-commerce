package com.afrotransact.orders.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.orders.application.OrdersSettings;
import com.afrotransact.orders.port.ProductFactsClient.Facts;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The checkout reads the seller's tax behaviour from catalog's public variant
 * ({@code contracts/examples/catalog-public-variant.json}, also checked against catalog's real answer): it belongs to
 * the owner's listing only and is never assumed for another seller.
 */
class HttpProductFactsClientTest {

    private static final Path FIXTURE = Path.of("..", "..", "..", "contracts", "examples",
            "catalog-public-variant.json");

    private HttpServer server;
    private HttpProductFactsClient client;

    @BeforeEach
    void start() throws IOException {
        byte[] body = Files.readAllBytes(FIXTURE);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/public/variants/", ex -> {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        OrdersSettings settings = new OrdersSettings("org_retail", Duration.ofMinutes(15), Duration.ofSeconds(30),
                Duration.ofHours(1), Set.of(), Map.of(), 50, 5, "America/Chicago");
        client = new HttpProductFactsClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(2), settings);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsTheOwnersBehaviourAndOrganisationFromTheListing() {
        Facts f = client.facts(List.of("AT0000VAR1")).get("AT0000VAR1");

        assertThat(f.ownerOrgId()).isEqualTo("org_amaka_kitchen");
        assertThat(f.productType()).isEqualTo("nuts-seeds");
        assertThat(f.sellerBehaviour()).containsEntry("sold_heated", false).containsEntry("utensils_provided", true)
                .containsEntry("heated_by_seller", false);
    }

    @Test
    void behaviourAppliesToTheOwnersLineOnly() {
        Facts f = client.facts(List.of("AT0000VAR1")).get("AT0000VAR1");

        assertThat(f.behaviourFor("org_amaka_kitchen")).containsEntry("utensils_provided", true);
        assertThat(f.behaviourFor("org_someone_else")).isEmpty();
        assertThat(f.behaviourFor(null)).isEmpty();
    }
}
