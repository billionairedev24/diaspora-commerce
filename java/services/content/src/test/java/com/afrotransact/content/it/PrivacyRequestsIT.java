package com.afrotransact.content.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Your Privacy Choices: anonymous and signed-in submissions, the staff queue and audited resolutions. */
class PrivacyRequestsIT extends ContentIntegrationTest {

    @Test
    void visitorsSubmitRequestsAndStaffResolveThem() {
        TestActors.anonymous();
        HttpResponse<String> created = post("/v1/privacy/requests",
                "{\"type\":\"access\",\"email\":\"Ada@Example.com\",\"state\":\"ny\",\"details\":\"All orders\",\"locale\":\"es\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode receipt = json(created);
        String ref = receipt.get("reference").asString();
        assertThat(ref).matches("PR-\\d{6}");
        assertThat(receipt.get("type").asString()).isEqualTo("access");
        assertThat(receipt.get("status").asString()).isEqualTo("RECEIVED");
        assertThat(receipt.get("dueAt").asString()).isNotBlank();

        HttpResponse<String> invalid = post("/v1/privacy/requests", "{\"type\":\"\",\"email\":\"nope\",\"state\":\"\"}");
        assertThat(invalid.statusCode()).isEqualTo(422);
        assertThat(invalid.body()).contains("content.invalid-privacy-request").contains("\"email\"")
                .contains("\"state\"").contains("\"type\"");

        assertThat(get("/v1/privacy/requests").statusCode()).isIn(401, 403);

        TestActors.as(new Actor("cust-42", PrincipalType.CUSTOMER, Optional.empty(), Set.of("customer"), Set.of()));
        String mine = json(post("/v1/privacy/requests",
                "{\"type\":\"optout\",\"email\":\"cust@example.com\",\"state\":\"CA\"}")).get("reference").asString();

        TestActors.as(TestActors.staff("support-1", "content:legal.view"));
        JsonNode queue = json(get("/v1/privacy/requests?status=RECEIVED"));
        assertThat(queue.toString()).contains(ref).contains(mine);
        JsonNode one = json(get("/v1/privacy/requests/" + mine.toLowerCase()));
        assertThat(one.get("accountSubject").asString()).isEqualTo("cust-42");
        assertThat(one.get("type").asString()).isEqualTo("optout");
        JsonNode anon = json(get("/v1/privacy/requests/" + ref));
        assertThat(anon.get("email").asString()).isEqualTo("ada@example.com");
        assertThat(anon.get("state").asString()).isEqualTo("NY");
        assertThat(anon.get("locale").asString()).isEqualTo("es");
        assertThat(anon.get("accountSubject").isNull()).isTrue();
        assertThat(anon.get("overdue").asBoolean()).isFalse();
        assertThat(post("/v1/privacy/requests/" + ref + "/resolution", "{\"outcome\":\"COMPLETED\"}").statusCode())
                .isEqualTo(403);

        TestActors.as(TestActors.staff("privacy-lead", "content:legal.edit"));
        assertThat(post("/v1/privacy/requests/" + ref + "/resolution", "{\"outcome\":\"DENIED\"}").statusCode())
                .isEqualTo(422);
        JsonNode done = json(post("/v1/privacy/requests/" + ref + "/resolution",
                "{\"outcome\":\"COMPLETED\",\"note\":\"Export emailed\"}"));
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("resolvedBy").asString()).isEqualTo("privacy-lead");
        assertThat(post("/v1/privacy/requests/" + ref + "/resolution", "{\"outcome\":\"DENIED\",\"note\":\"x\"}")
                .statusCode()).isEqualTo(409);
        assertThat(get("/v1/privacy/requests/PR-999999").statusCode()).isEqualTo(404);
        assertThat(jdbc.sql("select count(*) from audit_log where entity_id = :ref").param("ref", ref)
                .query(Long.class).single()).isEqualTo(2);
    }
}
