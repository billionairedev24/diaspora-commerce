package com.afrotransact.ai.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Settings > AI and MCP spend cap: today's spend by feature against the cap, from the ledger, for staff with ai:manage only. */
class SpendTodayIT extends AiGatewayIntegrationTest {

    private static final Actor STAFF = new Actor("staff-ai", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.operations_manager"), Set.of("ai:manage"));

    @Test
    void reportsTodaysLedgerByFeatureAndOnlyToStaffWithAiManage() throws Exception {
        TestActors.as(STAFF);
        JsonNode before = readJson(get("/v1/ai/usage/today"));
        assertThat(before.path("day").asString()).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
        assertThat(before.has("capMicros")).isTrue();
        assertThat(before.path("resetsAt").asString()).endsWith("T00:00:00Z");
        long callsBefore = before.path("calls").asLong();
        long spentBefore = before.path("spentMicros").asLong();

        String marker = unique("Palm oil");
        Actor member = new Actor(unique("member"), PrincipalType.SELLER_MEMBER, Optional.of(unique("org_ai")),
                Set.of("seller.owner"), Set.of("ai:use"));
        TestActors.as(member);
        WireMock.stubFor(WireMock.post("/v1/chat/completions").withRequestBody(WireMock.containing(marker))
                .willReturn(WireMock.okJson(OpenRouterStubs.completion("test/primary", "Huile de palme"))));
        HttpResponse<String> call = postJson("/v1/ai/translate", Map.of("text", marker, "targetLanguage", "fr"),
                "application/json");
        assertThat(call.statusCode()).as(call.body()).isEqualTo(200);

        TestActors.as(STAFF);
        JsonNode after = readJson(get("/v1/ai/usage/today"));
        assertThat(after.path("calls").asLong()).isEqualTo(callsBefore + 1);
        assertThat(after.path("spentMicros").asLong()).isGreaterThanOrEqualTo(spentBefore);
        assertThat(after.path("hasData").asBoolean()).isTrue();
        // the figures are the ledger's own: the features add up to the total
        long sum = 0;
        long calls = 0;
        for (JsonNode f : after.path("byFeature")) {
            sum += f.path("costMicros").asLong();
            calls += f.path("calls").asLong();
        }
        assertThat(sum).isEqualTo(after.path("spentMicros").asLong());
        assertThat(calls).isEqualTo(after.path("calls").asLong());
        assertThat(jdbc.sql("select coalesce(sum(cost_micros), 0) from ai_usage where usage_day = current_date at time zone 'utc'")
                .query(Long.class).single()).isEqualTo(after.path("spentMicros").asLong());

        // nobody else may read it
        TestActors.as(TestActors.customer(unique("shopper")));
        assertThat(get("/v1/ai/usage/today").statusCode()).isEqualTo(403);
        TestActors.as(member);
        assertThat(get("/v1/ai/usage/today").statusCode()).isEqualTo(403);
        TestActors.as(new Actor("seller-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of("ai:usage.read")));
        assertThat(get("/v1/ai/usage/today").statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
