package com.afrotransact.ai.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Fallback on provider errors, budgets (deny and degrade) and the embeddings contract used by search. */
class ModelRoutingIT extends AiGatewayIntegrationTest {

    @Test
    void fallsBackToTheSecondModelWhenTheFirstReturns500() throws Exception {
        String text = unique("Fresh plantain chips");
        TestActors.as(TestActors.seller(unique("seller"), "ai:use"));
        WireMock.stubFor(WireMock.post("/v1/chat/completions")
                .withRequestBody(WireMock.containing(text))
                .withRequestBody(WireMock.containing("\"model\":\"test/primary\""))
                .willReturn(WireMock.aResponse().withStatus(500).withHeader("Content-Type", "application/json")
                        .withBody(OpenRouterStubs.error(500, "upstream provider error"))));
        WireMock.stubFor(WireMock.post("/v1/chat/completions")
                .withRequestBody(WireMock.containing(text))
                .withRequestBody(WireMock.containing("\"model\":\"test/secondary\""))
                .willReturn(WireMock.okJson(OpenRouterStubs.completion("test/secondary", "Chips de banane frais"))));

        HttpResponse<String> response = postJson("/v1/ai/translate", Map.of("text", text, "targetLanguage", "fr"),
                "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = readJson(response);
        assertThat(body.path("translation").asString()).isEqualTo("Chips de banane frais");
        assertThat(body.path("model").asString()).isEqualTo("test/secondary");
        // primary: first attempt + one retry on 5xx, then the fallback model answers
        WireMock.verify(2, WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/chat/completions"))
                .withRequestBody(WireMock.containing(text))
                .withRequestBody(WireMock.containing("\"model\":\"test/primary\"")));
        WireMock.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/chat/completions"))
                .withRequestBody(WireMock.containing(text))
                .withRequestBody(WireMock.containing("\"model\":\"test/secondary\"")));
        Boolean fallback = jdbc.sql("select fallback from ai_usage where feature = 'translate' and model = "
                + "'test/secondary' order by occurred_at desc limit 1").query(Boolean.class).single();
        assertThat(fallback).isTrue();
    }

    @Test
    void exhaustedBudgetIsRejectedWithProblem() throws Exception {
        String subject = unique("heavy-user");
        TestActors.as(TestActors.seller(subject, "ai:use"));
        spend(subject, "embeddings", 5_000);

        HttpResponse<String> response = postJson("/v1/embeddings", Map.of("input", "egusi soup",
                "purpose", "search-query"), "application/json");

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("application/problem+json"));
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
        JsonNode problem = readJson(response);
        assertThat(problem.path("code").asString()).isEqualTo("ai.budget-exceeded");
        assertThat(problem.path("feature").asString()).isEqualTo("embeddings");
        assertThat(problem.path("scope").asString()).isEqualTo("user-tokens");
    }

    @Test
    void softBudgetLimitDegradesToTheCheapestModel() throws Exception {
        String subject = unique("thrifty-seller");
        String marker = unique("Kente scarf");
        TestActors.as(TestActors.seller(subject, "ai:use"));
        spend(subject, "listing-writer", 1_500);
        WireMock.stubFor(WireMock.post("/v1/chat/completions")
                .withRequestBody(WireMock.containing(marker))
                .willReturn(WireMock.okJson(OpenRouterStubs.completion("test/cheap", """
                        {"title":"Handwoven Kente Scarf","description":"A handwoven scarf.",\
                        "bullets":["Handwoven","Cotton"]}"""))));

        HttpResponse<String> response = postJson("/v1/ai/listing-writer", Map.of("productType", "SCARF",
                "attributes", Map.of("name", marker, "material", "cotton")), "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode draft = readJson(response);
        assertThat(draft.path("title").asString()).isEqualTo("Handwoven Kente Scarf");
        assertThat(draft.path("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.path("autoPublished").asBoolean()).isFalse();
        assertThat(draft.path("model").asString()).isEqualTo("test/cheap");
        WireMock.verify(0, WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/chat/completions"))
                .withRequestBody(WireMock.containing(marker))
                .withRequestBody(WireMock.containing("\"model\":\"test/strong\"")));
        Boolean degraded = jdbc.sql("select degraded from ai_usage where subject = :s").param("s", subject)
                .query(Boolean.class).single();
        assertThat(degraded).isTrue();
    }

    @Test
    void embeddingsMatchTheShapeTheGoSearchEmbedderSends() throws Exception {
        TestActors.as(TestActors.seller(unique("svc-search"), "ai:use"));
        String query = unique("spicy pepper soup");
        WireMock.stubFor(WireMock.post("/v1/embeddings")
                .withRequestBody(WireMock.containing(query))
                .willReturn(WireMock.okJson(OpenRouterStubs.embedding("test/embed", 0.125f, -0.5f, 0.75f))));

        // exactly what go/services/search/internal/adapter/embedder sends: map[string]string{input, purpose}
        HttpResponse<String> response = postJson("/v1/embeddings", Map.of("input", query, "purpose", "search-query"),
                "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = readJson(response);
        assertThat(body.path("embedding").isArray()).isTrue();
        assertThat(body.path("embedding").valueStream().map(JsonNode::floatValue).toList())
                .containsExactly(0.125f, -0.5f, 0.75f);
        assertThat(body.path("dimensions").asInt()).isEqualTo(3);
        WireMock.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/embeddings"))
                .withRequestBody(WireMock.matchingJsonPath("$.model", WireMock.equalTo("test/embed")))
                .withRequestBody(WireMock.matchingJsonPath("$.encoding_format", WireMock.equalTo("float")))
                .withRequestBody(WireMock.matchingJsonPath("$.input[0]", WireMock.equalTo(query))));
    }

    @Test
    void utilitiesNeedThePermission() throws Exception {
        TestActors.as(TestActors.customer(unique("shopper")));
        HttpResponse<String> response = postJson("/v1/ai/tagline", Map.of("storeName", "Mama Put"),
                "application/json");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(readJson(response).path("code").asString()).isEqualTo("auth.missing-permission");
    }

    private void spend(String subject, String feature, long tokens) {
        jdbc.sql("""
                insert into ai_usage_daily (usage_day, scope, scope_key, tokens, cost_micros)
                values (:day, 'USER', :key, :tokens, 0)
                """)
                .param("day", Date.valueOf(LocalDate.now(ZoneOffset.UTC)))
                .param("key", subject + "|" + feature)
                .param("tokens", tokens)
                .update();
    }
}
