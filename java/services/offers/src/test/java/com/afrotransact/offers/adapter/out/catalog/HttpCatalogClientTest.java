package com.afrotransact.offers.adapter.out.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.afrotransact.offers.port.CatalogClient.CatalogUnavailableException;
import com.afrotransact.offers.port.CatalogClient.CatalogVariant;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The HTTP adapter against a loopback stub of catalog-service (no external network). */
class HttpCatalogClientTest {

    private static final String PRODUCT = """
            {"id":"6f1c0d1e-4c1e-4a35-9f53-0d6c6b0c1a11","atin":"ATPROD0001","ownerKind":"SELLER",
             "sellerOrgId":"org_lagostextilehub00001","categoryId":"0b0e4b52-36e4-4bb4-8d0b-2b3e44d3f0aa",
             "title":"Jollof Seasoning","variants":[
               {"id":"1b5f1e8e-3a43-4bd7-9d1f-3c1e6a9d5b01","atin":"ATVAR00001","sellerSku":"JOL-8",
                "gtin":"00012345678905"},
               {"id":"2b5f1e8e-3a43-4bd7-9d1f-3c1e6a9d5b02","atin":"ATVAR00002","sellerSku":"JOL-16",
                "gtin":"4006381333931"}]}
            """;

    private HttpServer server;
    private HttpCatalogClient client;

    @BeforeEach
    void start() throws IOException {
        Map<String, String> routes = Map.of(
                "/v1/public/variants/ATVAR00001", "{\"product\":" + PRODUCT + ",\"variant\":{\"atin\":\"ATVAR00001\","
                        + "\"sellerSku\":\"JOL-8\",\"gtin\":\"00012345678905\"}}",
                "/v1/identifiers/gtin/12345678905", "{\"digits\":\"12345678905\",\"valid\":true,"
                        + "\"existingAtin\":\"ATPROD0001\"}",
                "/v1/identifiers/gtin/4006381333930", "{\"digits\":\"4006381333930\",\"valid\":true}",
                "/v1/public/products/ATPROD0001", PRODUCT);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, routes));
        server.start();
        client = new HttpCatalogClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(2), () -> "svc-offers-token");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void resolvesLiveVariantsByAtin() {
        CatalogVariant v = client.liveVariant("ATVAR00001").orElseThrow();

        assertThat(v.productAtin()).isEqualTo("ATPROD0001");
        assertThat(v.variantAtin()).isEqualTo("ATVAR00001");
        assertThat(v.ownerOrgId()).isEqualTo("org_lagostextilehub00001");
        assertThat(v.categoryId()).isNotNull();
        assertThat(client.liveVariant("ATMISSING1")).isEmpty();
    }

    @Test
    void resolvesTheVariantCarryingAGtinIgnoringLeadingZeros() {
        assertThat(client.liveVariantByGtin("12345678905")).get().extracting(CatalogVariant::variantAtin)
                .isEqualTo("ATVAR00001");
        assertThat(client.liveVariantByGtin("4006381333930")).as("valid GTIN without a product page").isEmpty();
    }

    @Test
    void serverErrorsMeanCatalogUnavailable() {
        assertThatThrownBy(() -> client.liveVariant("ATBOOM0001")).isInstanceOf(CatalogUnavailableException.class);
    }

    private static void respond(HttpExchange exchange, Map<String, String> routes) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = routes.get(path);
        int status = body != null ? 200 : path.contains("BOOM") ? 500 : 404;
        if (path.startsWith("/v1/identifiers/") && !"Bearer svc-offers-token".equals(
                exchange.getRequestHeaders().getFirst("Authorization"))) {
            status = 401; // GTIN lookups must carry offers' own client-credentials token
            body = null;
        }
        byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
