package com.afrotransact.content.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import com.afrotransact.content.application.ContentEvents;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** OpenFeature OFREP evaluation, stable bucketing, kill switch and the map-style evaluate endpoint. */
class FlagsIT extends ContentIntegrationTest {

    @Test
    void ofrepEvaluatesWithStableBucketsAndHonoursTheKillSwitch() {
        String key = "it.rollout-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = post("/v1/flags",
                "{\"key\":\"" + key + "\",\"description\":\"IT rollout\",\"enabled\":true,\"rolloutPercentage\":50}");
        assertThat(created.statusCode()).isEqualTo(201);

        JsonNode one = json(post("/ofrep/v1/evaluate/flags/" + key, ctx("user-1", "US")));
        assertThat(one.get("key").asString()).isEqualTo(key);
        assertThat(one.get("reason").asString()).isEqualTo("SPLIT");
        assertThat(one.get("variant").asString()).isIn("on", "off");
        boolean value = one.get("value").asBoolean();
        for (int i = 0; i < 3; i++) {
            assertThat(json(post("/ofrep/v1/evaluate/flags/" + key, ctx("user-1", "US"))).get("value").asBoolean())
                    .isEqualTo(value);
        }
        int on = 0;
        for (int i = 0; i < 300; i++) {
            if (json(post("/ofrep/v1/evaluate/flags/" + key, ctx("subject-" + i, null))).get("value").asBoolean()) {
                on++;
            }
        }
        assertThat(on).isBetween(110, 190);

        HttpResponse<String> missing = post("/ofrep/v1/evaluate/flags/does.not-exist", ctx("user-1", "US"));
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("errorCode").asString()).isEqualTo("FLAG_NOT_FOUND");

        assertThat(post("/v1/flags/" + key + "/kill-switch", "{\"on\":true}").statusCode()).isEqualTo(200);
        JsonNode killed = json(post("/ofrep/v1/evaluate/flags/" + key, ctx("user-1", "US")));
        assertThat(killed.get("value").asBoolean()).isFalse();
        assertThat(killed.get("reason").asString()).isEqualTo("DISABLED");
        assertThat(events(ContentEvents.FLAG_CHANGED, key)).isEqualTo(2);
    }

    @Test
    void bulkOfrepSupportsEtags() {
        HttpResponse<String> bulk = post("/ofrep/v1/evaluate/flags", ctx("user-42", "US"));
        assertThat(bulk.statusCode()).isEqualTo(200);
        String etag = bulk.headers().firstValue("ETag").orElseThrow();
        boolean found = false;
        for (JsonNode f : json(bulk).get("flags")) {
            if (f.get("key").asString().equals("lola.assistant")) {
                found = true;
                assertThat(f.get("value").asBoolean()).isTrue();
                assertThat(f.get("reason").asString()).isEqualTo("STATIC");
            }
        }
        assertThat(found).isTrue();
        assertThat(post("/ofrep/v1/evaluate/flags", ctx("user-42", "US"), "If-None-Match", etag).statusCode()).isEqualTo(304);
        assertThat(post("/ofrep/v1/evaluate/flags", ctx("user-43", "US"), "If-None-Match", etag).statusCode()).isEqualTo(200);
    }

    @Test
    void evaluateReturnsAMapOfFlags() {
        JsonNode r = json(post("/v1/flags/evaluate",
                "{\"subject\":\"user-1\",\"context\":{\"market\":\"CA\"},\"flags\":[\"checkout.express-pay\",\"lola.assistant\"]}"));
        assertThat(r.get("flags").get("lola.assistant").asBoolean()).isTrue();
        assertThat(r.get("flags").get("checkout.express-pay").asBoolean()).as("US-only flag in Canada").isFalse();
        assertThat(r.get("flags").size()).isEqualTo(2);
    }

    private static String ctx(String targetingKey, String market) {
        return "{\"context\":{\"targetingKey\":\"" + targetingKey + "\"" + (market == null ? "" : ",\"market\":\"" + market + "\"")
                + "}}";
    }
}
