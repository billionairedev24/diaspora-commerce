package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.afrotransact.seller.application.ApplicationView;
import com.afrotransact.seller.application.ReviewCommand;
import com.afrotransact.seller.application.SellerEvents;
import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.Offering;
import com.afrotransact.seller.domain.ReviewAction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A flagged application reaches the applicant: the reviewer flags it (the applicant is not told), the final approver
 * returns it with a message and what is missing, the applicant sees that message but none of the internal steps, fixes
 * it and resubmits.
 */
class OnboardingReturnToApplicantIT extends SellerIntegrationTest {

    @Test
    void aFlaggedApplicationIsReturnedFixedAndResubmitted() {
        String user = unique("flag");
        ApplicationView submitted = submittedApplication(user, "seller_growth", Cadence.MONTHLY, Offering.PRODUCTS,
                null, null);
        String appId = submitted.application().id();
        String reviewer = unique("reviewer");
        String approver = unique("approver");

        // Step 1 flags it for senior review: the applicant keeps seeing "in review".
        TestActors.as(TestActors.staff(reviewer, SellerPermissions.APPLICATIONS_REVIEW));
        assertThat(post("/v1/applications/" + appId + "/review", Map.of("action", "FLAG", "reasonCode",
                "PERMIT_MISSING", "note", "INTERNAL: owner matches a watch-list entry", "failedChecks",
                List.of("Owner screening"))).text("/status")).isEqualTo("FLAGGED");
        TestActors.as(TestActors.applicant(user));
        Response whileFlagged = get("/v1/applications/me");
        assertThat(whileFlagged.text("/status")).as("a flagged application looks like any other in review")
                .isEqualTo("PENDING_REVIEW");
        assertThat(whileFlagged.body().path("failedChecks")).isEmpty();
        assertThat(whileFlagged.body().path("reviewerId").isNull()).isTrue();
        assertThat(actions(whileFlagged)).as("flag, reviewer approval and send-back never reach the applicant")
                .containsExactly("STARTED", "SUBMITTED");
        assertThat(whileFlagged.body().toString()).doesNotContain("INTERNAL").doesNotContain(reviewer);
        assertThat(put("/v1/applications/me/steps/plan", Map.of("planCode", "seller_pro")).text("/code"))
                .as("not editable until it is returned").isEqualTo("seller.application-locked");

        // Step 2 returns it to the applicant with a message and the missing item.
        TestActors.as(TestActors.staff(approver, SellerPermissions.APPLICATIONS_APPROVE));
        Response noItems = post("/v1/applications/" + appId + "/decision", Map.of("action", "REQUEST_INFO",
                "reasonCode", "DOCUMENT_UNREADABLE", "note", "Please send a clearer copy"));
        assertThat(noItems.status()).isEqualTo(422);
        assertThat(noItems.text("/code")).isEqualTo("seller.requested-items-required");
        Response returned = post("/v1/applications/" + appId + "/decision", Map.of("action", "REQUEST_INFO",
                "reasonCode", "DOCUMENT_UNREADABLE", "note", "Please send a clearer copy of your formation document",
                "requestedItems", List.of("Formation document")));
        assertThat(returned.status()).isEqualTo(200);
        assertThat(returned.text("/status")).isEqualTo("INFO_REQUESTED");
        assertThat(events(SellerEvents.APPLICATION_INFO_REQUESTED, appId)).isEqualTo(1);

        // The applicant sees why, what is missing and nobody's name; the internal flag stays inside.
        TestActors.as(TestActors.applicant(user));
        Response mine = get("/v1/applications/me");
        assertThat(mine.text("/status")).isEqualTo("INFO_REQUESTED");
        assertThat(mine.body().path("requestedItems").get(0).asString()).isEqualTo("Formation document");
        assertThat(actions(mine)).containsExactly("STARTED", "SUBMITTED", "REQUEST_INFO");
        JsonNode message = mine.body().path("timeline").get(2);
        assertThat(message.path("note").asString()).isEqualTo("Please send a clearer copy of your formation document");
        assertThat(message.path("actorId").isNull() || message.path("actorId").isMissingNode()).isTrue();
        assertThat(mine.body().toString()).doesNotContain("INTERNAL").doesNotContain(reviewer).doesNotContain(approver);

        // Staff still see the whole history.
        TestActors.as(TestActors.staff(reviewer, SellerPermissions.APPLICATIONS_REVIEW));
        assertThat(actions(get("/v1/applications/" + appId))).containsExactly("STARTED", "SUBMITTED", "FLAG",
                "REQUEST_INFO");

        // The applicant fixes it and resubmits; it is back in the queue as an ordinary submission.
        TestActors.as(TestActors.applicant(user));
        assertThat(put("/v1/applications/me/steps/catalog-compliance", Map.of("categories", List.of("fashion",
                "home"), "agreementsAccepted", true, "w9Certified", true, "w9SignerName", "Ade")).status())
                .isEqualTo(200);
        Response resubmitted = post("/v1/applications/me/submit", Map.of());
        assertThat(resubmitted.text("/status")).isEqualTo("PENDING_REVIEW");
        assertThat(resubmitted.body().path("submissionCount").asInt()).isEqualTo(2);
    }

    @Test
    void aRejectionTellsTheApplicantWhyButNotWho() {
        String user = unique("reject");
        String appId = submittedApplication(user, "seller_growth", Cadence.MONTHLY, Offering.PRODUCTS, null, null)
                .application().id();
        String reviewer = unique("reviewer");
        TestActors.as(TestActors.staff(reviewer, SellerPermissions.APPLICATIONS_REVIEW));
        reviews.review(appId, new ReviewCommand(ReviewAction.APPROVE, "ALL_CHECKS_PASSED", "INTERNAL looks fine", null,
                null));
        TestActors.as(TestActors.staff(unique("approver"), SellerPermissions.APPLICATIONS_APPROVE));
        Response rejected = post("/v1/applications/" + appId + "/decision", Map.of("action", "REJECT", "reasonCode",
                "OTHER", "note", "We can't onboard this business type yet"));
        assertThat(rejected.text("/status")).isEqualTo("REJECTED");

        TestActors.as(TestActors.applicant(user));
        Response mine = get("/v1/applications/me");
        assertThat(mine.text("/status")).isEqualTo("REJECTED");
        assertThat(actions(mine)).containsExactly("STARTED", "SUBMITTED", "REJECT");
        assertThat(mine.body().path("timeline").get(2).path("note").asString())
                .isEqualTo("We can't onboard this business type yet");
        assertThat(mine.body().toString()).doesNotContain("INTERNAL").doesNotContain(reviewer);
    }

    private static List<String> actions(Response response) {
        return response.body().path("timeline").valueStream().map(t -> t.path("action").asString()).toList();
    }
}
