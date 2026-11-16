package com.afrotransact.ai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.ai.application.AiSpendCapPolicy;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Settings > AI and MCP spend cap against a real database: the migration, the version rule, the permission gate, the audit
 * trail and outbox record, and that the guard follows the saved cap straight away (lowering blocks, raising reopens).
 */
class SpendCapIT extends AiGatewayIntegrationTest {

    private static final Actor CAP_ADMIN = new Actor("staff-cap-admin", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.developer"), Set.of("ai:manage", "ai:cap.manage"));
    private static final Actor VIEWER = new Actor("staff-viewer", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.product_owner"), Set.of("ai:manage"));

    AiSpendCapPolicy policy;

    @BeforeEach
    void wirePolicy(@Autowired AiSpendCapPolicy spendCapPolicy) {
        this.policy = spendCapPolicy;
    }

    @AfterEach
    void restoreTheDefault() {
        jdbc.sql("delete from ai_spend_cap").update();
        jdbc.sql("delete from ai_spend_cap_change").update();
        jdbc.sql("delete from platform_outbox where type = 'ai.spend-cap.changed'").update();
        policy.invalidate();
    }

    @Test
    void migrationCreatesTheSettingAndTheHistoryTables() {
        assertThat(jdbc.sql("select count(*) from information_schema.tables where table_name in "
                + "('ai_spend_cap', 'ai_spend_cap_change')").query(Long.class).single()).isEqualTo(2);
        // a negative cap or a one-character reason can never be stored, whatever the application does
        assertThatThrownBy(() -> jdbc.sql("insert into ai_spend_cap values "
                + "(1, -1, 1, 'x', now(), 'valid reason')").update()).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.sql("insert into ai_spend_cap values "
                + "(1, 1, 1, 'x', now(), 'no')").update()).isInstanceOf(Exception.class);
    }

    @Test
    void startsOnTheDeploymentDefaultThenSavesWithVersionReasonHistoryAndAnOutboxRecord() throws Exception {
        TestActors.as(CAP_ADMIN);
        HttpResponse<String> first = get();
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("ETag")).contains("\"0\"");
        JsonNode before = readJson(first);
        assertThat(before.path("source").asString()).isEqualTo("default");
        assertThat(before.path("capMicros").asLong()).isEqualTo(20_000_000);
        assertThat(before.path("ceilingMicros").asLong()).isEqualTo(20_000_000);
        assertThat(before.path("canChange").asBoolean()).isTrue();
        assertThat(before.path("history")).isEmpty();

        HttpResponse<String> saved = put("\"0\"", Map.of("capMicros", 5_000_000, "reason", "Launch week"));
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(saved.headers().firstValue("ETag")).contains("\"1\"");
        JsonNode after = readJson(saved);
        assertThat(after.path("capMicros").asLong()).isEqualTo(5_000_000);
        assertThat(after.path("source").asString()).isEqualTo("saved");
        assertThat(after.path("changedBy").asString()).isEqualTo("staff-cap-admin");
        assertThat(after.path("reason").asString()).isEqualTo("Launch week");
        assertThat(after.path("history").get(0).path("previousWasDefault").asBoolean()).isTrue();
        assertThat(after.path("history").get(0).path("newMicros").asLong()).isEqualTo(5_000_000);

        // a second save names the first as "before"
        HttpResponse<String> second = put("\"1\"", Map.of("capMicros", 3_000_000, "reason", "Quieter again"));
        assertThat(second.statusCode()).isEqualTo(200);
        JsonNode latest = readJson(second).path("history").get(0);
        assertThat(latest.path("previousMicros").asLong()).isEqualTo(5_000_000);
        assertThat(latest.path("previousWasDefault").asBoolean()).isFalse();

        // the audit event is in the transactional outbox with before, after and reason
        assertThat(jdbc.sql("select count(*) from platform_outbox where type = 'ai.spend-cap.changed'")
                .query(Long.class).single()).isEqualTo(2);
        Map<String, Object> audit = jdbc.sql("select payload->>'previousMicros' as before, payload->>'newMicros' as after, "
                + "payload->>'reason' as reason, payload->>'changedBy' as who, payload->>'previousSource' as source "
                + "from platform_outbox where type = 'ai.spend-cap.changed' order by occurred_at desc limit 1")
                .query().singleRow();
        assertThat(audit).containsEntry("before", "5000000").containsEntry("after", "3000000")
                .containsEntry("reason", "Quieter again").containsEntry("who", "staff-cap-admin")
                .containsEntry("source", "SAVED");
    }

    @Test
    void refusesWithoutIfMatchWithAStaleVersionAboveTheCeilingAndWithABadReason() throws Exception {
        TestActors.as(CAP_ADMIN);
        assertProblem(put(null, Map.of("capMicros", 1_000_000, "reason", "No version")), 428,
                "ai.spend-cap.precondition-required");
        assertProblem(put("\"7\"", Map.of("capMicros", 1_000_000, "reason", "Wrong version")), 412,
                "ai.spend-cap.version-mismatch");
        assertProblem(put("\"0\"", Map.of("capMicros", 20_010_000, "reason", "Too much")), 422,
                "ai.spend-cap.above-ceiling");
        assertProblem(put("\"0\"", Map.of("capMicros", -10_000, "reason", "Negative")), 422, "ai.spend-cap.invalid");
        assertProblem(put("\"0\"", Map.of("capMicros", 1_000_000, "reason", "no")), 422, "ai.spend-cap.reason-invalid");
        assertThat(readJson(get()).path("source").asString()).isEqualTo("default");
        assertThat(jdbc.sql("select count(*) from ai_spend_cap_change").query(Long.class).single()).isZero();
    }

    @Test
    void onlyStaffWithTheCapPermissionMayChangeItAndEveryoneElseGetsAProblem() throws Exception {
        Map<String, Object> body = Map.of("capMicros", 1_000_000, "reason", "Not allowed");
        for (Actor actor : new Actor[] {VIEWER, TestActors.customer(unique("shopper")),
            new Actor("seller-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                    Set.of("ai:usage.read", "ai:cap.manage")),
            new Actor("svc-mcp", PrincipalType.SERVICE, Optional.empty(), Set.of(), Set.of("ai:use", "mcp:staff")),
            TestActors.seller(unique("member"), "ai:use")}) {
            TestActors.as(actor);
            assertProblem(put("\"0\"", body), 403, "auth.missing-permission");
        }
        assertThat(jdbc.sql("select count(*) from ai_spend_cap").query(Long.class).single()).isZero();

        TestActors.as(VIEWER);
        JsonNode view = readJson(get());
        assertThat(view.path("canChange").asBoolean()).isFalse();
        TestActors.as(TestActors.customer(unique("shopper")));
        assertThat(get().statusCode()).isEqualTo(403);
    }

    @Test
    void theGuardFollowsTheSavedCapAtOnceLoweringBlocksAndRaisingReopens() throws Exception {
        String marker = unique("Shea butter");
        WireMock.stubFor(WireMock.post("/v1/chat/completions").withRequestBody(WireMock.containing(marker))
                .willReturn(WireMock.okJson(OpenRouterStubs.completion("test/primary", "Beurre de karite"))));
        Actor member = new Actor(unique("member"), PrincipalType.SELLER_MEMBER, Optional.of(unique("org_ai")),
                Set.of("seller.owner"), Set.of("ai:use"));
        Map<String, String> call = Map.of("text", marker, "targetLanguage", "fr");

        TestActors.as(member);
        assertThat(postJson("/v1/ai/translate", call, "application/json").statusCode()).isEqualTo(200);

        // 0 = AI off: the next call is the existing 'cap reached' 429
        TestActors.as(CAP_ADMIN);
        long version = readJson(get()).path("version").asLong();
        assertThat(put("\"" + version + "\"", Map.of("capMicros", 0, "reason", "Pause AI")).statusCode()).isEqualTo(200);
        TestActors.as(member);
        HttpResponse<String> blocked = postJson("/v1/ai/translate", call, "application/json");
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(readJson(blocked).path("code").asString()).isEqualTo("ai.budget-exceeded");
        assertThat(readJson(blocked).path("scope").asString()).isEqualTo("global");

        // raising it the same day reopens AI without waiting for tomorrow
        TestActors.as(CAP_ADMIN);
        assertThat(put("\"1\"", Map.of("capMicros", 10_000_000, "reason", "Back on")).statusCode()).isEqualTo(200);
        TestActors.as(member);
        assertThat(postJson("/v1/ai/translate", call, "application/json").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get() throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/ai/spend-cap")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String ifMatch, Map<String, Object> body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/ai/spend-cap"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("problem+json");
        assertThat(readJson(response).path("code").asString()).isEqualTo(code);
    }
}
