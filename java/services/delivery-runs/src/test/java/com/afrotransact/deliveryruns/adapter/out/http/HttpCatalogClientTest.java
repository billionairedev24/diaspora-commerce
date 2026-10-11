package com.afrotransact.deliveryruns.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.afrotransact.deliveryruns.domain.PreorderResolver.ProductFacts;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** catalog-service contract as delivery-runs uses it: the public variant view names its category by id only. */
class HttpCatalogClientTest {

    private WireMockServer server;
    private HttpCatalogClient client;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        client = new HttpCatalogClient("http://localhost:" + server.port(), Duration.ofSeconds(2),
                Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC));
        server.stubFor(WireMock.get("/v1/categories").willReturn(WireMock.okJson("[{\"id\":\"c-rice\","
                + "\"name\":\"Grains & rice\",\"path\":\"Groceries › Pantry › Grains & rice\"}]")));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private void variant(String atin, String productJson) {
        server.stubFor(WireMock.get("/v1/public/variants/" + atin).willReturn(WireMock.okJson(
                "{\"product\":" + productJson + ",\"variant\":{\"atin\":\"" + atin + "\"}}")));
    }

    @Test
    void aCategoryIdIsResolvedToItsPathOnce() {
        variant("AT1", "{\"categoryId\":\"c-rice\",\"preorderEligible\":true,\"shipsNow\":false}");
        variant("AT2", "{\"categoryId\":\"c-rice\",\"shipsNow\":true}");
        server.stubFor(WireMock.get("/v1/public/variants/AT3").willReturn(WireMock.aResponse().withStatus(404)));

        Map<String, ProductFacts> facts = client.facts(List.of("AT1", "AT2", "AT3"));

        assertThat(facts).containsOnlyKeys("AT1", "AT2");
        assertThat(facts.get("AT1")).isEqualTo(new ProductFacts("AT1", "Groceries › Pantry › Grains & rice", false,
                true));
        assertThat(facts.get("AT2").shipsNow()).isTrue();
        server.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/v1/categories")));
    }

    @Test
    void aCategoryNameOnTheProductWins() {
        variant("AT4", "{\"categoryName\":\"Pantry\",\"categoryId\":\"c-rice\"}");
        assertThat(client.facts(List.of("AT4")).get("AT4").category()).isEqualTo("Pantry");
        server.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo("/v1/categories")));
    }
}
