package com.afrotransact.content.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import com.afrotransact.content.application.ContentPermissions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Staff edit a legal document as a draft, a second person publishes it, and an older version can be restored. */
class LegalAuthoringIT extends ContentIntegrationTest {

    private static final String DOC = "/v1/legal/seller-fees";

    private static String body(String version, String en, boolean banner, Long rowVersion) {
        return "{\"version\":\"" + version + "\",\"effectiveDate\":\"2027-01-01\",\"draftBanner\":" + banner
                + ",\"bodies\":{\"en\":" + JSON.writeValueAsString(en) + ",\"fr\":\"# Frais\"},\"needsReview\":[\"fr\"],"
                + "\"changeSummary\":\"Lower fee\"" + (rowVersion == null ? "" : ",\"rowVersion\":" + rowVersion) + "}";
    }

    @Test
    void savingADraftChangesNothingPublicAndASecondPersonPublishesIt() {
        String liveBefore = json(get(DOC)).get("version").asString();
        TestActors.as(TestActors.staff("editor-a", ContentPermissions.LEGAL_EDIT));
        assertThat(get(DOC + "/draft").statusCode()).isEqualTo(404);

        HttpResponse<String> saved = put(DOC + "/draft", body("9.1", "# Seller fees\n\n## Fee\n\nTen percent.", true, null));
        assertThat(saved.statusCode()).isEqualTo(200);
        JsonNode draft = json(saved);
        assertThat(draft.get("rowVersion").asLong()).isEqualTo(1);
        assertThat(draft.get("updatedBy").asString()).isEqualTo("editor-a");
        assertThat(draft.get("needsReview").toString()).isEqualTo("[\"fr\"]");
        assertThat(json(get(DOC)).get("version").asString()).isEqualTo(liveBefore);
        assertThat(json(get(DOC + "/history")).toString()).doesNotContain("9.1");
        JsonNode overview = json(get("/v1/legal/overview"));
        assertThat(overview.size()).isEqualTo(21);
        JsonNode fees = null;
        for (JsonNode entry : overview) {
            if ("seller-fees".equals(entry.get("key").asString())) {
                fees = entry;
            }
        }
        assertThat(fees).isNotNull();
        assertThat(fees.get("hasDraft").asBoolean()).isTrue();
        assertThat(fees.get("draftUpdatedBy").asString()).isEqualTo("editor-a");

        // A stale save is refused, and the editor cannot approve their own draft.
        assertThat(put(DOC + "/draft", body("9.1", "# x", true, 0L)).statusCode()).isEqualTo(409);
        HttpResponse<String> own = post(DOC + "/draft/publication", "{\"reason\":\"Ready to go\",\"rowVersion\":1}");
        assertThat(own.statusCode()).isEqualTo(403);
        assertThat(json(own).toString()).contains("content.second-approver-required");

        TestActors.as(TestActors.staff("approver-b", ContentPermissions.LEGAL_EDIT));
        assertThat(post(DOC + "/draft/publication", "{\"reason\":\"\",\"rowVersion\":1}").statusCode()).isEqualTo(400);
        assertThat(post(DOC + "/draft/publication", "{\"reason\":\"Ready to go\",\"rowVersion\":7}").statusCode()).isEqualTo(409);
        HttpResponse<String> published = post(DOC + "/draft/publication", "{\"reason\":\"Counsel approved the wording\",\"rowVersion\":1}");
        assertThat(published.statusCode()).isEqualTo(201);
        assertThat(json(published).get("version").asString()).isEqualTo("9.1");
        assertThat(json(published).get("createdBy").asString()).isEqualTo("approver-b");

        TestActors.reset();
        assertThat(json(get(DOC)).get("version").asString()).isEqualTo("9.1");
        JsonNode history = json(get(DOC + "/history"));
        assertThat(history.get(0).get("version").asString()).isEqualTo("9.1");
        assertThat(history.get(0).get("publishedBy").asString()).isEqualTo("approver-b");
        assertThat(history.get(1).get("status").asString()).isEqualTo("ARCHIVED");
        assertThat(get(DOC + "/draft").statusCode()).isEqualTo(404);
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'legal.draft-published' and entity_id = 'seller-fees'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void aFinalDocumentCannotKeepCounselMarkers() {
        String doc = "/v1/legal/accessibility";
        TestActors.as(TestActors.staff("editor-a", ContentPermissions.LEGAL_EDIT));
        assertThat(put(doc + "/draft", body("9.1", "# Accessibility\n\nWe reply in [[LEGAL: Q17 days]].", false, null)).statusCode()).isEqualTo(200);
        TestActors.as(TestActors.staff("approver-b", ContentPermissions.LEGAL_EDIT));
        HttpResponse<String> refused = post(doc + "/draft/publication", "{\"reason\":\"Ready to go\",\"rowVersion\":1}");
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("[[LEGAL");
        TestActors.as(TestActors.staff("editor-a", ContentPermissions.LEGAL_EDIT));
        assertThat(delete(doc + "/draft").statusCode()).isEqualTo(204);
        assertThat(get(doc + "/draft").statusCode()).isEqualTo(404);
    }

    @Test
    void anOlderVersionIsRestoredAsADraftAndPublishedUnderANewNumber() {
        String doc = "/v1/legal/ip-policy";
        TestActors.as(TestActors.staff("editor-a", ContentPermissions.LEGAL_EDIT));
        JsonNode source = json(get(doc + "/versions/" + json(get(doc)).get("version").asString() + "/source"));
        String current = source.get("version").asString();
        HttpResponse<String> restored = post(doc + "/draft/restoration", "{\"version\":\"" + current + "\"}");
        assertThat(restored.statusCode()).isEqualTo(201);
        assertThat(json(restored).get("changeSummary").asString()).isEqualTo("Restores version " + current);
        assertThat(json(restored).get("bodies").get("en").asString()).isEqualTo(source.get("bodies").get("en").asString());
        assertThat(post(doc + "/draft/restoration", "{\"version\":\"" + current + "\"}").statusCode()).isEqualTo(409);
        assertThat(get(doc + "/versions/0.1/source").statusCode()).isEqualTo(404);
        TestActors.as(TestActors.staff("viewer", ContentPermissions.LEGAL_VIEW));
        assertThat(get(doc + "/draft").statusCode()).isEqualTo(200);
        assertThat(put(doc + "/draft", body("99.1", "# x", true, 1L)).statusCode()).isEqualTo(403);
        TestActors.anonymous();
        assertThat(get(doc + "/draft").statusCode()).isEqualTo(403);
        TestActors.as(TestActors.staff("editor-a", ContentPermissions.LEGAL_EDIT));
        assertThat(delete(doc + "/draft").statusCode()).isEqualTo(204);
    }
}
