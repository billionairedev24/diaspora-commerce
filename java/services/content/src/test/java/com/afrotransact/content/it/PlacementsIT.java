package com.afrotransact.content.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.content.application.ContentEvents;
import com.afrotransact.content.application.ContentPermissions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Placement workflow over HTTP through to public resolution, bundles and outbox events. */
class PlacementsIT extends ContentIntegrationTest {

    @Test
    void paidPlacementNeedsASecondApproverThenResolvesForItsAudience() {
        TestActors.as(TestActors.staff("maya", ContentPermissions.PLACEMENTS_EDIT, ContentPermissions.PLACEMENTS_APPROVE));
        HttpResponse<String> created = post("/v1/placements", hero(Instant.now().minusSeconds(3600), 900));
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode placement = json(created);
        String id = placement.get("id").asString();
        assertThat(placement.get("status").asString()).isEqualTo("DRAFT");
        assertThat(placement.get("payload").get("title").asString()).isEqualTo("Jerk season is every season.");

        assertThat(json(post("/v1/placements/" + id + "/submit", "")).get("status").asString()).isEqualTo("IN_REVIEW");
        HttpResponse<String> selfApproval = post("/v1/placements/" + id + "/approve", "");
        assertThat(selfApproval.statusCode()).isEqualTo(422);
        assertThat(json(selfApproval).get("code").asString()).isEqualTo("content.four-eyes-required");

        TestActors.as(TestActors.staff("lead", ContentPermissions.PLACEMENTS_APPROVE));
        JsonNode approved = json(post("/v1/placements/" + id + "/approve", ""));
        assertThat(approved.get("status").asString()).isEqualTo("LIVE");
        assertThat(approved.get("approvedBy").asString()).isEqualTo("lead");
        assertThat(events(ContentEvents.PLACEMENTS_PUBLISHED, "US")).isPositive();

        TestActors.anonymous();
        String path = "/v1/placements/resolve?market=US&slot=hero_carousel&zip=77042&device=MOBILE";
        HttpResponse<String> resolved = get(path);
        assertThat(resolved.statusCode()).isEqualTo(200);
        assertThat(resolved.headers().firstValue("Cache-Control").orElse("")).contains("public");
        JsonNode first = json(resolved).get("placements").get(0);
        assertThat(first.get("id").asString()).isEqualTo(id);
        assertThat(first.get("label").asString()).isEqualTo("Sponsored");
        assertThat(first.get("advertiser").asString()).isEqualTo("Kingston Kitchen");
        assertThat(first.get("payload").get("theme").asString()).isEqualTo("ink");

        String etag = resolved.headers().firstValue("ETag").orElseThrow();
        assertThat(get(path, "If-None-Match", etag).statusCode()).isEqualTo(304);

        assertThat(ids(get("/v1/placements/resolve?market=US&slot=hero_carousel&zip=10001&device=MOBILE"))).doesNotContain(id);
        assertThat(ids(get("/v1/placements/resolve?market=US&slot=hero_carousel&zip=77042&device=DESKTOP"))).doesNotContain(id);
        HttpResponse<String> capped = get(path + "&seen=" + id + ":3");
        assertThat(ids(capped)).doesNotContain(id);
        assertThat(capped.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(ids(get(path + "&seen=" + id + ":2"))).contains(id);

        HttpResponse<String> bundle = get("/v1/placements/bundles/US");
        assertThat(bundle.statusCode()).isEqualTo(200);
        assertThat(bundle.headers().firstValue("Cache-Control").orElse("")).contains("s-maxage");
        JsonNode heroes = json(bundle).get("slots").get("hero_carousel");
        List<String> bundled = new ArrayList<>();
        heroes.forEach(h -> bundled.add(h.get("id").asString()));
        assertThat(bundled).contains(id, "plc_hero_party_jollof");
        assertThat(json(get("/v1/placements/bundles/CA")).get("slots").get("hero_carousel").toString()).doesNotContain(id);

        TestActors.as(TestActors.staff("lead", ContentPermissions.PLACEMENTS_APPROVE));
        assertThat(json(post("/v1/placements/" + id + "/end", "")).get("status").asString()).isEqualTo("ENDED");
        TestActors.anonymous();
        assertThat(ids(get(path))).doesNotContain(id);
        assertThat(jdbc.sql("select count(*) from audit_log where entity_type = 'placement' and entity_id = :id")
                .param("id", id).query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void scheduledPlacementWaitsForItsWindow() {
        TestActors.as(TestActors.staff("maya", ContentPermissions.PLACEMENTS_EDIT, ContentPermissions.PLACEMENTS_APPROVE));
        String id = json(post("/v1/placements", announcement(Instant.now().plusSeconds(86_400)))).get("id").asString();
        post("/v1/placements/" + id + "/submit", "");
        JsonNode approved = json(post("/v1/placements/" + id + "/approve", ""));
        assertThat(approved.get("status").asString()).isEqualTo("SCHEDULED");
        assertThat(ids(get("/v1/placements/resolve?market=US&slot=announcement_bar"))).doesNotContain(id)
                .startsWith("plc_ann_free_delivery");
    }

    @Test
    void payloadMustMatchTheSlotSchema() {
        TestActors.as(TestActors.staff("maya", ContentPermissions.PLACEMENTS_EDIT));
        String body = """
                {"slot":"announcement_bar","name":"Bad","payload":{"tone":"purple","text":"Hi","extra":1},
                 "schedule":{"startsAt":"2026-01-01T00:00:00Z"}}
                """;
        HttpResponse<String> r = post("/v1/placements", body);
        assertThat(r.statusCode()).isEqualTo(422);
        JsonNode problem = json(r);
        assertThat(problem.get("code").asString()).isEqualTo("content.invalid-payload");
        assertThat(problem.get("fieldErrors").propertyNames()).anyMatch(k -> k.startsWith("payload"));
    }

    @Test
    void editingNeedsThePermission() {
        TestActors.as(TestActors.staff("ops", ContentPermissions.PLACEMENTS_VIEW));
        assertThat(get("/v1/placements").statusCode()).isEqualTo(200);
        assertThat(get("/v1/slots").statusCode()).isEqualTo(200);
        assertThat(post("/v1/placements", announcement(Instant.now())).statusCode()).isEqualTo(403);
    }

    private static List<String> ids(HttpResponse<String> r) {
        assertThat(r.statusCode()).isEqualTo(200);
        List<String> out = new ArrayList<>();
        json(r).get("placements").forEach(p -> out.add(p.get("id").asString()));
        return out;
    }

    private static String hero(Instant start, int priority) {
        return """
                {"slot":"hero_carousel","name":"Kingston Kitchen jerk (IT)","priority":%d,"paid":true,
                 "advertiser":"Kingston Kitchen",
                 "payload":{"theme":"ink","kicker":"Kingston Kitchen","title":"Jerk season is every season.",
                            "sub":"Blue Mountain jerk paste.","cta":"Shop now","href":"/search?q=jerk","label":"Sponsored"},
                 "targeting":{"markets":["US"],"zipPrefixes":["770"],"devices":["MOBILE"]},
                 "schedule":{"startsAt":"%s"},
                 "frequencyCap":{"impressions":3,"windowHours":24}}
                """.formatted(priority, start);
    }

    private static String announcement(Instant start) {
        return """
                {"slot":"announcement_bar","name":"Later","priority":1,
                 "payload":{"tone":"gold","icon":"ph ph-star","text":"Coming tomorrow","cta":"See","href":"/deals"},
                 "schedule":{"startsAt":"%s"}}
                """.formatted(start);
    }
}
