package com.afrotransact.content.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Home settings (defaults, mandatory reason, 422 fields, 409, audit, revert), the community registry and S423: the
 * migrations leave no seeded demo placement behind.
 */
class HomeSettingsIT extends ContentIntegrationTest {

    @AfterEach
    void cleanUp() {
        jdbc.sql("delete from home_settings_version where version > 0").update();
        jdbc.sql("delete from community_entry").update();
        TestActors.reset();
    }

    @Test
    void migrationsRemoveTheSeededDemoPlacementsButKeepEditedOnes() {
        int seeded = jdbc.sql("select count(*) from placement where created_by = 'seed' and version = 0")
                .query(Integer.class).single();
        assertThat(seeded).as("no untouched seeded placement survives V20").isZero();
        int demo = jdbc.sql("select count(*) from placement where id in ('plc_tiles_communities', 'plc_hero_diwali', "
                + "'plc_ann_festive_stock', 'plc_banner_kente')").query(Integer.class).single();
        assertThat(demo).isZero();
    }

    @Test
    void defaultsLoadWithNoRowAndEqualTheDocumentedThresholds() {
        JsonNode s = json(get("/v1/home-settings"));
        assertThat(s.get("version").asInt()).isZero();
        JsonNode communities = s.get("modules").get("communities");
        assertThat(communities.get("minUnits").asInt()).isEqualTo(3);
        assertThat(communities.get("unitMinProducts").asInt()).isEqualTo(8);
        assertThat(communities.get("unitMinSellers").asInt()).isEqualTo(2);
        assertThat(s.get("caps").get("sellerShare").asInt()).isEqualTo(2);
    }

    @Test
    void saveNeedsAReasonValidatesAndIsAuditedWithRevert() {
        String modules = json(get("/v1/home-settings")).get("modules").toString();
        String caps = json(get("/v1/home-settings")).get("caps").toString();
        String body = "{\"expectedVersion\":0,\"reason\":\"%s\",\"settings\":{\"dynamic\":\"shadow\",\"modules\":" + modules
                + ",\"caps\":" + caps + ",\"departmentLabels\":{}}}";
        assertThat(put("/v1/home-settings", body.formatted("")).statusCode()).isEqualTo(422);
        String negative = body.replace("\"minUnits\":3", "\"minUnits\":-3").formatted("tighten");
        HttpResponse<String> bad = put("/v1/home-settings", negative);
        assertThat(bad.statusCode()).isEqualTo(422);
        assertThat(bad.body()).contains("modules.communities.minUnits");

        HttpResponse<String> ok = put("/v1/home-settings", body.formatted("Shadow first, owner asked"));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(json(ok).get("version").asInt()).isEqualTo(1);
        assertThat(put("/v1/home-settings", body.formatted("again")).statusCode()).as("stale version").isEqualTo(409);

        JsonNode versions = json(get("/v1/home-settings/versions"));
        assertThat(versions.get(0).get("reason").asString()).isEqualTo("Shadow first, owner asked");
        assertThat(versions.get(0).get("changes").toString()).contains("dynamic");
        int audited = jdbc.sql("select count(*) from audit_log where action = 'home-settings.updated'").query(Integer.class).single();
        assertThat(audited).isPositive();

        assertThat(post("/v1/home-settings/revert", "{\"toVersion\":1,\"reason\":\"\"}").statusCode()).isEqualTo(422);
    }

    @Test
    void communityRegistryIsPublicToReadAndStaffOnlyToWrite() {
        TestActors.anonymous();
        assertThat(put("/v1/community-registry/nigerian", "{}").statusCode()).isIn(401, 403);
        TestActors.reset();
        String entry = "{\"labels\":{\"en\":\"Nigerian\",\"fr\":\"Nigérian\"},\"aliases\":[\"naija\"],\"reason\":\"%s\"}";
        assertThat(put("/v1/community-registry/nigerian", entry.formatted("")).statusCode()).isEqualTo(422);
        assertThat(put("/v1/community-registry/nigerian", entry.formatted("Merge spellings")).statusCode()).isEqualTo(200);
        assertThat(put("/v1/community-registry/ghanaian",
                "{\"labels\":{\"en\":\"Ghanaian\"},\"aliases\":[\"Naija\"],\"reason\":\"clash\"}").statusCode())
                .as("an alias cannot belong to two communities").isEqualTo(422);
        assertThat(json(get("/v1/community-registry")).size()).isEqualTo(1);
        assertThat(delete("/v1/community-registry/nigerian?reason=Remove+it").statusCode()).isEqualTo(204);
    }

    @Test
    void placementsResolveAsOfAFutureInstantWithoutCaching() {
        HttpResponse<String> r = get("/v1/placements/resolve?market=US&at=2031-01-01T00:00:00Z");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    }
}
