package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.afrotransact.seller.application.SellerEvents;
import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.Offering;
import org.junit.jupiter.api.Test;

/**
 * Review decisions are idempotent over HTTP: the gateway's retry of a slow final approval (same Idempotency-Key), a
 * second click (new key) and a repeat without a key all answer 200 with the approved application, and the seller is
 * approved once (one seller.org.approved, one "You're approved" email, one subscription). Anyone else is told it is
 * already approved, by whom and when.
 */
class ReviewIdempotencyIT extends SellerIntegrationTest {

    private static final Map<String, Object> FINAL_APPROVE = Map.of("action", "FINAL_APPROVE",
            "reasonCode", "ALL_CHECKS_PASSED", "actorName", "Ada Approver");

    @Test
    void repeatingAFinalApprovalAnswersTheApprovedApplicationAndApprovesTheSellerOnce() {
        String user = unique("idem");
        String appId = submittedApplication(user, "seller_growth", Cadence.MONTHLY, Offering.PRODUCTS, null, null)
                .application().id();
        String orgId = get("/v1/applications/me").text("/orgId");
        String reviewer = unique("reviewer");
        String approver = unique("approver");

        TestActors.as(TestActors.staff(reviewer, SellerPermissions.APPLICATIONS_REVIEW));
        Map<String, Object> approve = Map.of("action", "APPROVE", "reasonCode", "ALL_CHECKS_PASSED",
                "actorName", "Rita Reviewer");
        assertThat(post("/v1/applications/" + appId + "/review", approve, "review-key-0001").text("/status"))
                .isEqualTo("AWAITING_FINAL_APPROVAL");
        Response reviewAgain = post("/v1/applications/" + appId + "/review", approve, "review-key-0001");
        assertThat(reviewAgain.status()).isEqualTo(200);
        assertThat(reviewAgain.text("/status")).isEqualTo("AWAITING_FINAL_APPROVAL");

        TestActors.as(TestActors.staff(approver, SellerPermissions.APPLICATIONS_APPROVE,
                SellerPermissions.APPLICATIONS_REVIEW));
        Response first = post("/v1/applications/" + appId + "/decision", FINAL_APPROVE, "final-key-0001");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.text("/status")).isEqualTo("APPROVED");
        String decidedAt = first.text("/decidedAt");
        assertThat(decidedAt).isNotNull();

        // The gateway's retry (same key), a second click (new key) and a plain repeat: 200, nothing changes.
        for (Response again : new Response[] {
            post("/v1/applications/" + appId + "/decision", FINAL_APPROVE, "final-key-0001"),
            post("/v1/applications/" + appId + "/decision", FINAL_APPROVE, "final-key-0002"),
            post("/v1/applications/" + appId + "/decision", FINAL_APPROVE)}) {
            assertThat(again.status()).as(again.body().toString()).isEqualTo(200);
            assertThat(again.text("/status")).isEqualTo("APPROVED");
            assertThat(again.text("/finalApproverId")).isEqualTo(approver);
            assertThat(again.text("/decidedAt")).isEqualTo(decidedAt);
        }
        assertThat(events(SellerEvents.ORG_APPROVED, orgId)).as("one approval email").isEqualTo(1);
        assertThat(events(SellerEvents.APPLICATION_APPROVED, appId)).isEqualTo(1);
        assertThat(events(SellerEvents.SUBSCRIPTION_CHANGED, orgId)).as("one subscription").isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from application_event where application_id = :a and action = "
                + "'FINAL_APPROVE'").param("a", appId).query(Long.class).single()).isEqualTo(1);
        assertThat(get("/v1/applications/" + appId).body().path("timeline").get(3).path("actorName").asString())
                .isEqualTo("Ada Approver");

        // A key reused for a different decision is refused.
        Response reused = post("/v1/applications/" + appId + "/decision", Map.of("action", "REJECT",
                "reasonCode", "OTHER", "note", "x"), "final-key-0001");
        assertThat(reused.status()).isEqualTo(409);
        assertThat(reused.text("/code")).isEqualTo("seller.idempotency-key-reused");

        // The approver trying a different decision: already approved by you.
        Response mine = post("/v1/applications/" + appId + "/decision", Map.of("action", "REJECT",
                "reasonCode", "OTHER", "note", "x"));
        assertThat(mine.status()).isEqualTo(409);
        assertThat(mine.text("/detail")).startsWith("This application is already approved (by you, ");

        // Another approver: already approved, by whom and when; never "needs a reviewer decision first".
        TestActors.as(TestActors.staff(unique("approver2"), SellerPermissions.APPLICATIONS_APPROVE));
        Response other = post("/v1/applications/" + appId + "/decision", FINAL_APPROVE, "final-key-0003");
        assertThat(other.status()).isEqualTo(409);
        assertThat(other.text("/code")).isEqualTo("seller.already-decided");
        assertThat(other.text("/detail")).startsWith("This application is already approved (by Ada Approver, ")
                .endsWith(" UTC)").doesNotContain("reviewer decision");
        assertThat(other.text("/decision")).isEqualTo("APPROVED");
        assertThat(other.text("/decidedBy")).isEqualTo("Ada Approver");
        assertThat(other.text("/decidedAt")).isNotNull();

        // Step 1 again: already approved, too.
        TestActors.as(TestActors.staff(reviewer, SellerPermissions.APPLICATIONS_REVIEW));
        Response late = post("/v1/applications/" + appId + "/review", approve);
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.text("/code")).isEqualTo("seller.already-decided");
        assertThat(events(SellerEvents.ORG_APPROVED, orgId)).isEqualTo(1);

        // The applicant never sees staff names.
        TestActors.as(TestActors.applicant(user));
        assertThat(get("/v1/applications/me").body().path("timeline").get(3).path("actorName").isNull()).isTrue();
        TestActors.reset();
    }
}
