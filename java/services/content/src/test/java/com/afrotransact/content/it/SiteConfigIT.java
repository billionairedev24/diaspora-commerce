package com.afrotransact.content.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import com.afrotransact.content.application.ContentEvents;
import com.afrotransact.content.application.ContentPermissions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Public site config with ETag + revision, validated edits, conflicts, audit and change events. */
class SiteConfigIT extends ContentIntegrationTest {

    @Test
    void etagRevisionAndValidatedUpdates() {
        HttpResponse<String> first = get("/v1/site-config");
        assertThat(first.statusCode()).isEqualTo(200);
        String etag = first.headers().firstValue("ETag").orElseThrow();
        int revision = Integer.parseInt(first.headers().firstValue("X-Config-Revision").orElseThrow());
        JsonNode config = json(first);
        assertThat(config.get("revision").asInt()).isEqualTo(revision);
        assertThat(config.get("socials").toString()).contains("instagram").doesNotContain("whatsapp");
        assertThat(config.get("contacts").get("phone").asString()).isEqualTo("+1 (833) 555-0199");
        assertThat(config.get("contacts").get("pressEmail").asString()).isEqualTo("press@afrotransact.com");
        assertThat(config.get("legal").size()).isEqualTo(9);
        assertThat(config.get("locales").toString()).contains("\"dir\":\"rtl\"");
        assertThat(config.get("smsProgramName").asString()).isNotBlank();
        assertThat(get("/v1/site-config", "If-None-Match", etag).statusCode()).isEqualTo(304);

        ObjectNode settings = (ObjectNode) json(get("/v1/site-config/settings"));
        assertThat(settings.get("socials").toString()).contains("whatsapp");
        String originalName = settings.get("smsProgramName").asString();

        ObjectNode bad = settings.deepCopy();
        bad.put("expectedRevision", revision);
        ((ObjectNode) bad.get("socials").get(1)).put("url", "https://www.facebook.com/not-instagram");
        HttpResponse<String> rejected = put("/v1/site-config", bad.toString());
        assertThat(rejected.statusCode()).isEqualTo(422);
        assertThat(json(rejected).get("fieldErrors").has("socials.instagram")).isTrue();

        ObjectNode good = settings.deepCopy();
        good.put("expectedRevision", revision);
        good.put("summary", "Rename SMS program");
        good.put("smsProgramName", "AfroTransact Texts");
        HttpResponse<String> saved = put("/v1/site-config", good.toString());
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(json(saved).get("revision").asInt()).isEqualTo(revision + 1);

        HttpResponse<String> after = get("/v1/site-config", "If-None-Match", etag);
        assertThat(after.statusCode()).isEqualTo(200);
        assertThat(after.headers().firstValue("ETag").orElseThrow()).isNotEqualTo(etag);
        assertThat(json(after).get("revision").asInt()).isEqualTo(revision + 1);
        assertThat(json(get("/v1/legal/sms-terms")).get("body").asString()).contains("AfroTransact Texts is a text-message program");

        HttpResponse<String> stale = put("/v1/site-config", good.toString());
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(json(stale).get("code").asString()).isEqualTo("content.stale-revision");

        JsonNode changed = lastEvent(ContentEvents.SITE_CONFIG_CHANGED);
        assertThat(changed.get("revision").asInt()).isEqualTo(revision + 1);
        assertThat(changed.get("changedSections").toString()).isEqualTo("[\"smsProgramName\"]");
        assertThat(jdbc.sql("select count(*) from audit_log where entity_type = 'site-config' and entity_id = :r")
                .param("r", String.valueOf(revision + 1)).query(Long.class).single()).isEqualTo(1);
        assertThat(json(get("/v1/site-config/revisions")).get(0).get("summary").asString()).isEqualTo("Rename SMS program");

        good.put("expectedRevision", revision + 1);
        good.put("smsProgramName", originalName);
        assertThat(put("/v1/site-config", good.toString()).statusCode()).isEqualTo(200);
    }

    @Test
    void onlySiteEditorsMaySave() {
        TestActors.as(TestActors.staff("support", ContentPermissions.SITE_VIEW));
        assertThat(get("/v1/site-config/settings").statusCode()).isEqualTo(200);
        assertThat(put("/v1/site-config", "{\"expectedRevision\":1}").statusCode()).isEqualTo(403);
    }
}
