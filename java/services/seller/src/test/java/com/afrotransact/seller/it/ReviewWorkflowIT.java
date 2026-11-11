package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.seller.application.EscalationService;
import com.afrotransact.seller.application.SellerEvents;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.Offering;
import com.afrotransact.seller.port.StaffDirectory.StaffPerson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Application review workflow on PostgreSQL: queues, who holds the case, send-back, the SLA/escalation settings and
 * the escalation job (run twice: it escalates once).
 */
class ReviewWorkflowIT extends SellerIntegrationTest {

    private static final String REVIEW = "applications:review";
    private static final String APPROVE = "applications:approve";
    private static final String ASSIGN = "applications:assign";
    private final StaffPerson amina = new StaffPerson("amina-it", "Amina Bello");
    private final StaffPerson rita = new StaffPerson("rita-it", "Rita Okoro");
    private final StaffPerson adam = new StaffPerson("adam-it", "Adam Ade");
    private final StaffPerson sue = new StaffPerson("sue-it", "Sue Supervisor");

    EscalationService escalation;

    @BeforeEach
    void wireEscalation(@Autowired EscalationService escalationService) {
        this.escalation = escalationService;
    }

    private String submit() {
        TestActors.roster(REVIEW, amina, rita);
        TestActors.roster(APPROVE, adam, rita);
        TestActors.roster(ASSIGN, sue);
        return submittedApplication(unique("wf"), "seller_growth", Cadence.MONTHLY, Offering.PRODUCTS, null, null)
                .application().id();
    }

    private JsonNode row(String queue, String appId) {
        for (JsonNode r : get("/v1/applications?queue=" + queue + "&size=200").body().path("items")) {
            if (appId.equals(r.path("id").asString())) {
                return r;
            }
        }
        return null;
    }

    /** The console sends the signed-in person's name with every action. */
    private Response assignment(String appId, String action, String assigneeId) {
        String name = TestActors.current().subject().equals(amina.id()) ? amina.name()
                : TestActors.current().subject().equals(rita.id()) ? rita.name() : sue.name();
        return post("/v1/applications/" + appId + "/assignment", assigneeId == null
                ? Map.of("action", action, "actorName", name)
                : Map.of("action", action, "assigneeId", assigneeId, "actorName", name));
    }

    @Test
    void aSubmittedApplicationWaitsInTheReviewPoolAndAnyViewerSeesWhoHasIt() {
        String appId = submit();
        TestActors.as(TestActors.staff("viewer-it", APPROVE)); // an approver: not in the review purview
        JsonNode r = row("AWAITING_REVIEW", appId);
        assertThat(r).isNotNull();
        assertThat(r.path("workflow").path("holder").asString()).isEqualTo("any reviewer");
        assertThat(r.path("workflow").path("stage").asString()).isEqualTo("REVIEW");
        assertThat(r.path("workflow").path("slaState").asString()).isEqualTo("ON_TIME");
        assertThat(r.path("workflow").path("canClaim").asBoolean()).isFalse();
        assertThat(row("MY", appId)).as("an approver cannot claim a review").isNull();
        // viewing is not acting
        assertThat(post("/v1/applications/" + appId + "/review",
                Map.of("action", "APPROVE", "reasonCode", "ALL_CHECKS_PASSED")).status()).isEqualTo(403);
        // the detail view says the same and masks personal data
        assertThat(get("/v1/applications/" + appId).text("/workflow/holder")).isEqualTo("any reviewer");

        TestActors.as(TestActors.staff(amina.id(), REVIEW));
        assertThat(row("MY", appId)).as("unassigned cases I may claim are in my queue").isNotNull();
        Response claimed = assignment(appId, "CLAIM", null);
        assertThat(claimed.status()).isEqualTo(200);
        assertThat(claimed.text("/workflow/holder")).isEqualTo("with Amina Bello");
        assertThat(assignment(appId, "CLAIM", null).status()).as("repeat is idempotent").isEqualTo(200);

        TestActors.as(TestActors.staff(rita.id(), REVIEW));
        assertThat(row("MY", appId)).as("assigned to someone else").isNull();
        assertThat(assignment(appId, "CLAIM", null).status()).isEqualTo(409);
        // supervisors see everything and reassign
        TestActors.as(TestActors.staff(sue.id(), ASSIGN));
        assertThat(row("AWAITING_REVIEW", appId).path("workflow").path("holder").asString())
                .isEqualTo("with Amina Bello");
        assertThat(assignment(appId, "ASSIGN", rita.id()).text("/workflow/assigneeName")).isEqualTo("Rita Okoro");
        assertThat(assignment(appId, "ASSIGN", sue.id()).status()).as("a supervisor cannot review").isEqualTo(422);
        // timeline names people
        JsonNode timeline = get("/v1/applications/" + appId).body().path("timeline");
        assertThat(timeline.toString()).contains("Amina Bello").contains("Rita Okoro");
    }

    @Test
    void stepOneMovesTheCaseToApprovalAndSendBackReturnsItWithTheBanner() {
        String appId = submit();
        TestActors.as(TestActors.staff(rita.id(), REVIEW, APPROVE));
        assertThat(post("/v1/applications/" + appId + "/review", Map.of("action", "APPROVE",
                "reasonCode", "ALL_CHECKS_PASSED", "actorName", "Rita Okoro")).status()).isEqualTo(200);
        JsonNode r = row("AWAITING_APPROVAL", appId);
        assertThat(r.path("workflow").path("holder").asString()).isEqualTo("any approver");
        // two-person rule per application: Rita did step 1 so she cannot hold or decide step 2
        assertThat(assignment(appId, "CLAIM", null).status()).isEqualTo(422);
        assertThat(row("MY", appId)).isNull();

        TestActors.as(TestActors.staff(adam.id(), APPROVE));
        assertThat(row("MY", appId)).isNotNull();
        assertThat(post("/v1/applications/" + appId + "/decision", Map.of("action", "SEND_BACK",
                "reasonCode", "OTHER", "note", "The TIN was not checked", "actorName", "Adam Ade")).status())
                .isEqualTo(200);
        JsonNode back = row("AWAITING_REVIEW", appId).path("workflow");
        assertThat(back.path("returnedByName").asString()).isEqualTo("Adam Ade");
        assertThat(back.path("returnNote").asString()).isEqualTo("The TIN was not checked");
        assertThat(back.path("returnCount").asInt()).isEqualTo(1);
        assertThat(back.path("holder").asString()).isEqualTo("any reviewer");
        assertThat(events(SellerEvents.APPLICATION_ASSIGNED, appId)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void settingsAreValidatedAuditedAndNeedTheSupervisorPermission() {
        submit();
        TestActors.as(TestActors.staff(rita.id(), REVIEW));
        assertThat(get("/v1/application-review-settings").status()).isEqualTo(200);
        assertThat(put("/v1/application-review-settings", settings(24, "x", null)).status()).isEqualTo(403);

        TestActors.as(TestActors.staff(sue.id(), ASSIGN));
        assertThat(put("/v1/application-review-settings", settings(0, "Tighten it", null)).status())
                .isEqualTo(422);
        Map<String, Object> levelTwoOnly = settings(12, "Tighten it", Map.of("level1", Map.of(),
                "level2", Map.of("userIds", List.of(sue.id()))));
        assertThat(put("/v1/application-review-settings", levelTwoOnly).status()).isEqualTo(422);
        Response ok = put("/v1/application-review-settings", settings(12, "Faster reviews for the launch", Map.of(
                "level1", Map.of("userIds", List.of(sue.id())), "level2", Map.of())));
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
        assertThat(ok.text("/reviewSlaHours")).isEqualTo("12");
        assertThat(ok.body().path("changes").get(0).path("reason").asString()).isEqualTo("Faster reviews for the launch");
        put("/v1/application-review-settings", settings(24, "Back to the default", Map.of()));
    }

    @Test
    void theEscalationJobRaisesTheLevelOnceAndTheCaseShowsInTheEscalatedQueue() {
        String appId = submit();
        TestActors.as(TestActors.staff(sue.id(), ASSIGN));
        Response saved = put("/v1/application-review-settings", settings(24, "Set the chain", Map.of(
                "level1", Map.of("userIds", List.of(sue.id())), "level2", Map.of())));
        assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
        jdbc.sql("update application_case set stage_entered_at = :t where application_id = :a")
                .param("t", Timestamp.from(Instant.now().minus(Duration.ofHours(30)))).param("a", appId)
                .update();
        assertThat(row("ESCALATED", appId)).isNull();
        escalation.runDue();
        escalation.runDue(); // a second replica or a re-run
        JsonNode r = row("ESCALATED", appId);
        assertThat(r).isNotNull();
        assertThat(r.path("workflow").path("escalationLevel").asInt()).isEqualTo(1);
        assertThat(r.path("workflow").path("slaState").asString()).isEqualTo("OVERDUE");
        assertThat(r.path("workflow").path("escalatedTo").toString()).contains("Sue Supervisor");
        assertThat(events(SellerEvents.APPLICATION_ESCALATED, appId)).isEqualTo(1);
        assertThat(get("/v1/applications?overdue=true&queue=ALL&size=200").body().toString()).contains(appId);
        // moving to the next stage de-escalates
        TestActors.as(TestActors.staff(rita.id(), REVIEW));
        post("/v1/applications/" + appId + "/review", Map.of("action", "APPROVE", "reasonCode", "ALL_CHECKS_PASSED"));
        assertThat(row("ESCALATED", appId)).isNull();
        TestActors.as(TestActors.staff(sue.id(), ASSIGN));
        put("/v1/application-review-settings", settings(24, "Back to the default", Map.of()));
    }

    private static Map<String, Object> settings(int reviewHours, String reason, Map<String, Object> chain) {
        Map<String, Object> body = new HashMap<>();
        body.put("reviewSlaHours", reviewHours);
        body.put("approvalSlaHours", 24);
        body.put("dueSoonPercent", 75);
        body.put("unassignedAfterHours", 4);
        body.put("level2AfterHours", 24);
        body.put("autoAssign", "NONE");
        body.put("enforceAssignee", false);
        body.put("sendBackToReviewer", false);
        body.put("reviewChain", chain == null ? Map.of() : chain);
        body.put("approvalChain", Map.of());
        body.put("reason", reason);
        return body;
    }
}
