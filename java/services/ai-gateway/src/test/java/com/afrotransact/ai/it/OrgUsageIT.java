package com.afrotransact.ai.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** S206: seller members' AI calls are attributed to their organisation and reported as credits per day. */
class OrgUsageIT extends AiGatewayIntegrationTest {

    @Test
    void sellerCallsAreReportedAsCreditsPerOrganisationAndDay() throws Exception {
        String org = unique("org_ai");
        String marker = unique("Shea butter");
        Actor member = new Actor(unique("member"), PrincipalType.SELLER_MEMBER, Optional.of(org),
                Set.of("seller.owner"), Set.of("ai:use"));
        TestActors.as(member);
        WireMock.stubFor(WireMock.post("/v1/chat/completions")
                .withRequestBody(WireMock.containing(marker))
                .willReturn(WireMock.okJson(OpenRouterStubs.completion("test/primary", "Beurre de karité"))));
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> r = postJson("/v1/ai/translate", Map.of("text", marker, "targetLanguage", "fr"),
                    "application/json");
            assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        }
        // A shopper's call is never billed to an organisation.
        TestActors.as(TestActors.customer(unique("shopper")));
        assertThat(jdbc.sql("select count(*) from ai_usage where org_id = :org").param("org", org)
                .query(Long.class).single()).isEqualTo(2);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String path = "/v1/ai/usage/orgs?from=" + today.minusDays(1) + "&to=" + today;
        assertThat(get(path).statusCode()).isEqualTo(403);

        TestActors.as(new Actor("seller-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of("ai:usage.read")));
        HttpResponse<String> response = get(path);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = readJson(response);
        assertThat(body.path("microsPerCredit").asLong()).isEqualTo(10_000);
        List<JsonNode> rows = new ArrayList<>();
        body.path("items").forEach(i -> {
            if (i.path("orgId").asString().equals(org)) {
                rows.add(i);
            }
        });
        assertThat(rows).hasSize(1);
        JsonNode row = rows.getFirst();
        assertThat(row.path("day").asString()).isEqualTo(today.toString());
        assertThat(row.path("calls").asLong()).isEqualTo(2);
        long cost = row.path("costMicros").asLong();
        assertThat(row.path("credits").asLong()).isEqualTo((cost + 9_999) / 10_000);

        assertThat(get("/v1/ai/usage/orgs?from=" + today + "&to=" + today.minusDays(3)).statusCode())
                .isEqualTo(422);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
