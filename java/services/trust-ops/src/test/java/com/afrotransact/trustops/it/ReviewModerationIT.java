package com.afrotransact.trustops.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.trustops.application.ModerationItemView;
import com.afrotransact.trustops.application.ModerationService;
import com.afrotransact.trustops.domain.ModerationItemType;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.domain.ModerationStatus;
import com.afrotransact.trustops.domain.ReviewSubmission;
import com.afrotransact.trustops.domain.TrustRuleViolation;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S243: customer reviews of bookings in the moderation queue. Decisions go to bookings-service (WireMock) with
 * trust-ops' service token; bookings' own decisions close the item.
 */
class ReviewModerationIT extends TrustOpsIntegrationTest {

    private final ModerationService moderation;

    @Autowired
    ReviewModerationIT(ModerationService moderation) {
        this.moderation = moderation;
    }

    private static ReviewSubmission review(String org, int rating, String text) {
        return new ReviewSubmission(UUID.randomUUID(), org, UUID.randomUUID(), "Box braids (medium)", "cust-42",
                "Adaeze N.", rating, text, Instant.parse("2026-10-09T12:00:00Z"));
    }

    private ModerationItemView queue(ReviewSubmission r) {
        return moderation.enqueueReview(UUID.randomUUID(), r).orElseThrow();
    }

    @Test
    void approvedReviewIsPublishedInBookingsWithTheServiceTokenAndAudited() {
        String org = orgId();
        ReviewSubmission r = review(org, 5, "Neat parts, finished on time");
        ModerationItemView queued = queue(r);
        UUID bookingId = r.bookingId();

        assertThat(queued.item().type()).isEqualTo(ModerationItemType.SERVICE_REVIEW);
        assertThat(queued.item().status()).isEqualTo(ModerationStatus.PENDING);
        assertThat(queued.item().product()).isNull();
        assertThat(queued.item().subjectId()).isEqualTo(bookingId);
        assertThat(queued.item().orgId()).isEqualTo(org);
        UPSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/ai/triage"))
                .withRequestBody(WireMock.matchingJsonPath("$.subjectType", WireMock.equalTo("REVIEW")))
                .withRequestBody(WireMock.matchingJsonPath("$.subjectId", WireMock.equalTo(bookingId.toString())))
                .withRequestBody(WireMock.matchingJsonPath("$.fields.text",
                        WireMock.equalTo("Neat parts, finished on time")))
                .withRequestBody(WireMock.notContaining("cust-42")));

        TestSupport.as(TestSupport.reviewModerator("amaka"));
        ModerationItemView approved = moderation.approve(queued.item().id(), "Genuine and on topic");

        assertThat(approved.item().status()).isEqualTo(ModerationStatus.APPROVED);
        assertThat(approved.item().decidedBy()).isEqualTo("amaka");
        UPSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/reviews/" + bookingId + "/approve"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-trust-ops"))
                .withRequestBody(WireMock.matchingJsonPath("$.note", WireMock.equalTo("Genuine and on topic"))));
        UPSTREAM.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/products/.*")));
        assertThat(jdbc.sql("select actor_id from audit_entry where action = 'trustops.review.approved' "
                        + "and entity_type = 'REVIEW' and entity_id = :id").param("id", bookingId.toString())
                .query(String.class).single()).isEqualTo("amaka");
        assertThat(outbox(bookingId.toString())).containsExactly("trustops.moderation.queued",
                "trustops.moderation.decided");
        JsonNode decided = JSON.readTree(jdbc.sql("select payload::text from platform_outbox where aggregate_id = :id "
                        + "and type = 'trustops.moderation.decided'").param("id", bookingId.toString())
                .query(String.class).single());
        assertThat(decided.path("itemType").asString()).isEqualTo("SERVICE_REVIEW");
        assertThat(decided.path("bookingId").asString()).isEqualTo(bookingId.toString());
        assertThat(decided.path("sellerOrgId").asString()).isEqualTo(org);
        assertThat(decided.path("rating").asInt()).isEqualTo(5);
        assertThat(decided.path("productId").isNull()).isTrue();

        // bookings then publishes booking.review.approved: the item is already decided, nothing changes.
        moderation.closeFromBookings(UUID.randomUUID(), bookingId, "APPROVED");
        assertThat(moderation.get(queued.item().id()).item().status()).isEqualTo(ModerationStatus.APPROVED);
    }

    @Test
    void rejectionNeedsAReviewReasonCodeAndIsSentToBookings() {
        ModerationItemView queued = queue(review(orgId(), 1, "Call me on 555-0101 for a cheaper braider"));
        UUID bookingId = queued.item().subjectId();
        TestSupport.as(TestSupport.reviewModerator("amaka"));

        assertThatThrownBy(() -> moderation.reject(queued.item().id(), ModerationReasonCode.COUNTERFEIT, null))
                .isInstanceOf(TrustRuleViolation.class).hasMessageContaining("cannot remove a review");
        assertThatThrownBy(() -> moderation.reject(queued.item().id(), ModerationReasonCode.OTHER, " "))
                .isInstanceOf(TrustRuleViolation.class);
        UPSTREAM.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/reviews/.*")));

        ModerationItemView rejected = moderation.reject(queued.item().id(),
                ModerationReasonCode.PERSONAL_INFORMATION, "Phone number in the text");
        assertThat(rejected.item().status()).isEqualTo(ModerationStatus.REJECTED);
        UPSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/reviews/" + bookingId + "/reject"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-trust-ops"))
                .withRequestBody(WireMock.matchingJsonPath("$.reasonCode", WireMock.equalTo("PERSONAL_INFORMATION")))
                .withRequestBody(WireMock.matchingJsonPath("$.note", WireMock.equalTo("Phone number in the text"))));
        assertThat(jdbc.sql("select count(*) from audit_entry where action = 'trustops.review.rejected' "
                + "and entity_id = :id").param("id", bookingId.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void decidingAReviewNeedsTheBookingsModerationPermission() {
        ModerationItemView queued = queue(review(orgId(), 4, "Lovely"));
        TestSupport.as(TestSupport.trustSafety("listing-only"));

        assertThat(moderation.queue(null, null, false, ModerationItemType.SERVICE_REVIEW, 0, 200).items())
                .anySatisfy(v -> assertThat(v.item().id()).isEqualTo(queued.item().id()));
        assertThatThrownBy(() -> moderation.approve(queued.item().id(), null))
                .isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> moderation.reject(queued.item().id(), ModerationReasonCode.SPAM, null))
                .isInstanceOf(ForbiddenActionException.class);
        UPSTREAM.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/reviews/.*")));
        assertThat(moderation.get(queued.item().id()).item().status()).isEqualTo(ModerationStatus.PENDING);
    }

    @Test
    void bookingsRefusalKeepsTheItemOpenAndBookingsOwnDecisionClosesIt() {
        ModerationItemView queued = queue(review(orgId(), 3, "OK"));
        UUID bookingId = queued.item().subjectId();
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/reviews/[^/]+/approve"))
                .willReturn(WireMock.aResponse().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"bookings.review-not-found\",\"detail\":\"No review for booking\"}")));
        TestSupport.as(TestSupport.reviewModerator("amaka"));

        assertThatThrownBy(() -> moderation.approve(queued.item().id(), null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("No review for booking");
        assertThat(moderation.get(queued.item().id()).item().status()).isEqualTo(ModerationStatus.PENDING);

        // A moderator decided in bookings directly (GET /v1/reviews?status=PENDING there).
        moderation.closeFromBookings(UUID.randomUUID(), bookingId, "REJECTED");
        ModerationItemView closed = moderation.get(queued.item().id());
        assertThat(closed.item().status()).isEqualTo(ModerationStatus.CLOSED);
        assertThat(closed.item().decidedBy()).isEqualTo("bookings");
        assertThat(closed.item().decisionNote()).isEqualTo("Decided in bookings: REJECTED");
        assertThat(outbox(bookingId.toString())).endsWith("trustops.moderation.decided");
    }

    @Test
    void duplicatesAreIgnoredAndTheQueueServesReviewsOverHttp() {
        ReviewSubmission r = review(orgId(), 2, "Started 40 minutes late");
        UUID eventId = UUID.randomUUID();
        ModerationItemView queued = moderation.enqueueReview(eventId, r).orElseThrow();
        assertThat(moderation.enqueueReview(eventId, r)).isEmpty();
        assertThat(moderation.enqueueReview(UUID.randomUUID(), r)).isEmpty();

        JsonNode page = ok(send("GET", "/v1/moderation/items?type=SERVICE_REVIEW&size=200", null));
        JsonNode item = null;
        for (JsonNode i : page.path("items")) {
            if (i.path("id").asString().equals(queued.item().id().toString())) {
                item = i;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.path("type").asString()).isEqualTo("SERVICE_REVIEW");
        assertThat(item.path("productId").isNull()).isTrue();
        assertThat(item.path("title").asString()).isEqualTo("★★☆☆☆ review of Box braids (medium)");
        assertThat(item.path("review").path("bookingId").asString()).isEqualTo(r.bookingId().toString());
        assertThat(item.path("review").path("rating").asInt()).isEqualTo(2);
        assertThat(item.path("review").path("text").asString()).isEqualTo("Started 40 minutes late");
        assertThat(item.path("review").path("reviewerName").asString()).isEqualTo("Adaeze N.");
        assertThat(item.path("sla").path("state").asString()).isEqualTo("ON_TRACK");

        JsonNode reviewCodes = ok(send("GET", "/v1/moderation/reason-codes?type=SERVICE_REVIEW", null));
        assertThat(reviewCodes.findValuesAsString("code")).containsExactly("ABUSIVE", "PERSONAL_INFORMATION",
                "SPAM", "OFF_TOPIC", "CONFLICT_OF_INTEREST", "OTHER");
        JsonNode listingCodes = ok(send("GET", "/v1/moderation/reason-codes", null));
        assertThat(listingCodes.findValuesAsString("code")).contains("COUNTERFEIT", "OTHER").doesNotContain("SPAM");

        HttpResponse<String> wrongCode = send("POST", "/v1/moderation/items/" + queued.item().id() + "/reject",
                Map.of("reasonCode", "WRONG_CATEGORY"));
        problem(wrongCode, 422, "trustops.reason-code-not-applicable");
        JsonNode rejected = ok(send("POST", "/v1/moderation/items/" + queued.item().id() + "/reject",
                Map.of("reasonCode", "OFF_TOPIC", "note", "About the salon parking")));
        assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
        assertThat(rejected.path("reasonCode").asString()).isEqualTo("OFF_TOPIC");
    }
}
