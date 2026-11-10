package com.afrotransact.taxreturns.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.port.ProductFacts.Facts;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The on-the-fly lookup reads {@code GET /v1/public/variants/{atin}} as catalog really answers it: the same fixture
 * ({@code contracts/examples/catalog-public-variant.json}) is checked against catalog's real response by catalog's
 * {@code PublicVariantContractTest}, so neither side can drift alone.
 */
class HttpProductFactsContractTest {

    private static final Path FIXTURE = Path.of("..", "..", "..", "contracts", "examples",
            "catalog-public-variant.json");

    private HttpServer server;
    private HttpProductFacts lookup;

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
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        TaxReturnsProperties props = new TaxReturnsProperties(null, null, new TaxReturnsProperties.Upstreams(
                base + "/oauth2/token", null, "", Duration.ofSeconds(5), base, base, base, base, base, base), null,
                null);
        lookup = new HttpProductFacts(RestClient.builder(), props, JsonMapper.builder().build());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void readsCategoryCodeTaxFactsAndTheOwnersBehaviour() {
        Facts f = lookup.lookup(List.of("AT0000VAR1")).get("AT0000VAR1");

        assertThat(f.categoryCode()).isEqualTo("nuts-seeds");
        assertThat(f.productType()).isEqualTo("nuts-seeds");
        assertThat(f.ownerOrgId()).isEqualTo("org_amaka_kitchen");
        assertThat(f.taxFacts()).containsEntry("servings_per_package", 4L).containsEntry("label_type", "NUTRITION_FACTS");
        assertThat(f.taxFacts().get("net_weight_oz")).hasToString("8.5");
        assertThat(f.sellerBehaviour()).containsEntry("sold_heated", false).containsEntry("utensils_provided", true);
    }
}
