package com.afrotransact.content.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import com.afrotransact.content.application.ContentEvents;
import com.afrotransact.content.application.ContentPermissions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Open roles with filters, validated applications with CAR-###### references, and role management. */
class CareersIT extends ContentIntegrationTest {

    private static final String JOB = "go-engineer-search-discovery-remote-us";

    @Test
    void openRolesFilterByTeamAndLocation() {
        JsonNode engineering = json(get("/v1/careers/jobs?team=Engineering"));
        assertThat(engineering.get("jobs").size()).isEqualTo(2);
        assertThat(engineering.get("teams").toString()).contains("Delivery", "Warehouse", "Design");
        assertThat(json(get("/v1/careers/jobs?location=Houston,%20TX")).get("jobs").size()).isEqualTo(3);
        assertThat(json(get("/v1/careers/jobs?team=Delivery&location=Atlanta,%20GA")).get("jobs").size()).isEqualTo(1);
        JsonNode job = json(get("/v1/careers/jobs/" + JOB));
        assertThat(job.get("pay").asString()).isEqualTo("$160k–$200k");
        assertThat(job.get("responsibilities").size()).isPositive();
    }

    @Test
    void applicationGetsAReference() {
        HttpResponse<String> invalid = post("/v1/careers/jobs/" + JOB + "/applications", """
                {"name":"A","email":"nope","linkedin":"https://example.com/ada","workAuthorization":"Maybe",
                 "resumeFileName":"cv.exe","consent":false}
                """);
        assertThat(invalid.statusCode()).isEqualTo(422);
        assertThat(json(invalid).get("fieldErrors").propertyNames()).contains("name", "email", "linkedin",
                "workAuthorization", "resumeAssetId", "resumeFileName", "consent");

        String valid = """
                {"name":"Ada Nwosu","email":"ada@example.com","linkedin":"https://www.linkedin.com/in/ada-nwosu",
                 "workAuthorization":"Yes","resumeAssetId":"med_8f2k3j4h","resumeFileName":"Ada Nwosu CV.pdf",
                 "consent":true}
                """;
        HttpResponse<String> first = post("/v1/careers/jobs/" + JOB + "/applications", valid);
        assertThat(first.statusCode()).isEqualTo(201);
        String reference = json(first).get("reference").asString();
        assertThat(reference).matches("^CAR-\\d{6}$");
        assertThat(json(first).get("jobId").asString()).isEqualTo(JOB);
        String second = json(post("/v1/careers/jobs/" + JOB + "/applications", valid)).get("reference").asString();
        assertThat(second).matches("^CAR-\\d{6}$").isNotEqualTo(reference);
        assertThat(events(ContentEvents.CAREER_APPLICATION_RECEIVED, reference)).isEqualTo(1);
        assertThat(post("/v1/careers/jobs/nope/applications", valid).statusCode()).isEqualTo(404);

        TestActors.as(TestActors.staff("recruiter", ContentPermissions.CAREERS_VIEW));
        assertThat(json(get("/v1/careers/applications?jobId=" + JOB)).toString()).contains(reference);
    }

    @Test
    void rolesAreManagedByCareersEditors() {
        String body = """
                {"id":"data-engineer-remote-us","job":{"title":"Data Engineer","team":"Engineering",
                 "location":"Remote (US)","type":"Full-time","pay":"$150k–$185k","summary":"Pipelines.",
                 "status":"OPEN"}}
                """;
        TestActors.anonymous();
        assertThat(post("/v1/careers/jobs", body).statusCode()).isEqualTo(403);
        TestActors.as(TestActors.staff("recruiter", ContentPermissions.CAREERS_EDIT));
        assertThat(post("/v1/careers/jobs", body).statusCode()).isEqualTo(201);
        assertThat(json(get("/v1/careers/jobs/data-engineer-remote-us")).get("status").asString()).isEqualTo("OPEN");
        assertThat(put("/v1/careers/jobs/data-engineer-remote-us", """
                {"title":"Data Engineer","team":"Engineering","location":"Remote (US)","type":"Full-time",
                 "pay":"$150k–$185k","summary":"Pipelines.","status":"CLOSED"}
                """).statusCode()).isEqualTo(200);
        assertThat(get("/v1/careers/jobs/data-engineer-remote-us").statusCode()).isEqualTo(404);
    }
}
