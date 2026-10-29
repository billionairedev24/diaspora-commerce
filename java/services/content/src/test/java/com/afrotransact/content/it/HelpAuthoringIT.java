package com.afrotransact.content.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import com.afrotransact.content.application.ContentPermissions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Staff edit a help page as a draft, publish it with a reason, read the history and restore an older revision. */
class HelpAuthoringIT extends ContentIntegrationTest {

    private static String draft(String text, Long rowVersion) {
        return "{\"topic\":\"ORDERS_DELIVERY\",\"title\":\"Shipping & delivery\",\"summary\":\"Carriers and pickup\","
                + "\"sections\":[{\"heading\":\"Pickup\",\"body\":" + JSON.writeValueAsString(text) + "}],"
                + "\"sortOrder\":10,\"changeSummary\":\"Pickup wording\"" + (rowVersion == null ? "" : ",\"rowVersion\":" + rowVersion) + "}";
    }

    @Test
    void aDraftIsPreviewedNotPublicUntilPublishedWithAReason() {
        String path = "/v1/help/articles/shipping";
        String liveBefore = json(get(path)).get("sections").get(0).get("heading").asString();
        TestActors.as(TestActors.staff("marketer", ContentPermissions.SITE_EDIT));
        assertThat(get(path + "/draft").statusCode()).isEqualTo(404);
        HttpResponse<String> saved = put(path + "/draft", draft("Collect from a locker.", null));
        assertThat(saved.statusCode()).isEqualTo(200);
        assertThat(json(saved).get("updatedBy").asString()).isEqualTo("marketer");
        assertThat(json(get("/v1/help/admin/articles")).toString()).contains("\"hasDraft\":true");
        assertThat(json(get(path)).get("sections").get(0).get("heading").asString()).isEqualTo(liveBefore);
        assertThat(put(path + "/draft", draft("x", 0L)).statusCode()).isEqualTo(409);

        assertThat(post(path + "/draft/publication", "{\"reason\":\"\",\"rowVersion\":1}").statusCode()).isEqualTo(400);
        HttpResponse<String> published = post(path + "/draft/publication", "{\"reason\":\"Pickup lockers are live\",\"rowVersion\":1}");
        assertThat(published.statusCode()).isEqualTo(201);
        assertThat(json(published).get("updatedBy").asString()).isEqualTo("marketer");
        assertThat(json(get(path)).get("sections").get(0).get("body").asString()).isEqualTo("Collect from a locker.");
        assertThat(get(path + "/draft").statusCode()).isEqualTo(404);

        JsonNode revisions = json(get(path + "/revisions"));
        assertThat(revisions.size()).isGreaterThanOrEqualTo(2);
        assertThat(revisions.get(0).get("reason").asString()).isEqualTo("Pickup lockers are live");
        assertThat(revisions.get(0).get("publishedBy").asString()).isEqualTo("marketer");
        assertThat(revisions.get(revisions.size() - 1).get("revision").asInt()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'help-article.draft-published' and entity_id = 'shipping'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void anOlderRevisionIsRestoredAsTheNewestOne() {
        String path = "/v1/help/articles/returns";
        TestActors.as(TestActors.staff("marketer", ContentPermissions.SITE_EDIT));
        String original = json(get(path + "/revisions/1")).get("sections").get(0).get("body").asString();
        assertThat(put(path + "/draft", draft("Changed text.", null)).statusCode()).isEqualTo(200);
        assertThat(post(path + "/draft/publication", "{\"reason\":\"Reworded the page\",\"rowVersion\":1}").statusCode()).isEqualTo(201);

        assertThat(post(path + "/revisions/1/restoration", "{\"reason\":\"Back to the original\"}").statusCode()).isEqualTo(201);
        assertThat(json(get(path)).get("sections").get(0).get("body").asString()).isEqualTo(original);
        JsonNode revisions = json(get(path + "/revisions"));
        assertThat(revisions.get(0).get("changeSummary").asString()).isEqualTo("Restores revision 1");
        assertThat(post(path + "/revisions/99/restoration", "{\"reason\":\"Back to the original\"}").statusCode()).isEqualTo(404);

        TestActors.as(TestActors.staff("support", ContentPermissions.SITE_VIEW));
        assertThat(get(path + "/revisions").statusCode()).isEqualTo(200);
        assertThat(post(path + "/revisions/1/restoration", "{\"reason\":\"Back to the original\"}").statusCode()).isEqualTo(403);
        assertThat(put(path + "/draft", draft("x", null)).statusCode()).isEqualTo(403);
    }

    @Test
    void directSavesAreRevisionedToo() {
        TestActors.as(TestActors.staff("marketer", ContentPermissions.SITE_EDIT));
        String page = "{\"topic\":\"COMPANY\",\"title\":\"Our story\",\"summary\":\"Who we are\","
                + "\"sections\":[{\"heading\":\"Hello\",\"body\":\"We are AfroTransact.\"}],\"status\":\"DRAFT\"}";
        assertThat(put("/v1/help/articles/our-story", page).statusCode()).isEqualTo(200);
        JsonNode revisions = json(get("/v1/help/articles/our-story/revisions"));
        assertThat(revisions.size()).isEqualTo(1);
        assertThat(revisions.get(0).get("reason").asString()).isEqualTo("Page created");
        assertThat(json(get("/v1/help/admin/articles")).toString()).contains("\"updatedBy\":\"marketer\"");
    }
}
