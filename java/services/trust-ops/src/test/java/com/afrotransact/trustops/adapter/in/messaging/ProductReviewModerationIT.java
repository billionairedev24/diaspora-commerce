package com.afrotransact.trustops.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.trustops.application.ModerationItemView;
import com.afrotransact.trustops.application.ModerationService;
import com.afrotransact.trustops.domain.ModerationItemType;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.domain.ModerationStatus;
import com.afrotransact.trustops.domain.TrustRuleViolation;
import com.afrotransact.trustops.it.TestSupport;
import com.afrotransact.trustops.it.TrustOpsIntegrationTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S324: verified-purchase product reviews in the moderation queue. catalog.review.* events (the listener is called
 * directly, with records shaped like catalog's outbox output) queue and close items; decisions go to catalog-service
 * (WireMock) with trust-ops' service token.
 */
class ProductReviewModerationIT extends TrustOpsIntegrationTest {

    private final CatalogReviewEventListener listener;
    private final ModerationService moderation;

    @Autowired
    ProductReviewModerationIT(CatalogReviewEventListener listener, ModerationService moderation) {
        this.listener = listener;
        this.moderation = moderation;
    }

    /** A CatalogReview as catalog-service's outbox writes it. */
    private static String reviewEvent(UUID reviewId, String org, String status, int rating, String text, int reports,
                                      String reportReason) {
        return """
                {"contentType":"PRODUCT_REVIEW","reviewId":"%s","productId":"5f1d0b8e-0c55-4c8b-9a51-3f7f2b0c1a10",
                 "atin":"AT7K2M9QXA","productTitle":"Jollof Spice Mix 500g","sellerOrgId":"%s","customerId":"cust-77",
                 "reviewerName":"Kemi A.","rating":%d,"title":"Not as hot as advertised","text":%s,
                 "photoUrls":["https://utfs.io/f/a.jpg","https://utfs.io/f/b.jpg"],"status":"%s",
                 "flags":["PHONE","LINK"],"createdAt":"2026-10-09T12:00:00Z","reportCount":%d,"reportReason":%s,
                 "moderatedBy":null,"moderatedAt":null,"reasonCode":null,"moderationNote":null,"replyText":null,
                 "repliedAt":null,"approvePath":"/v1/product-reviews/%s/approve",
                 "rejectPath":"/v1/product-reviews/%s/reject","removePath":"/v1/product-reviews/%s/remove"}
                """.formatted(reviewId, org, rating, text == null ? "null" : "\"" + text + "\"", status, reports,
                reportReason == null ? "null" : "\"" + reportReason + "\"", reviewId, reviewId, reviewId);
    }

    private static ConsumerRecord<String, String> rec(String key, String type, UUID eventId, String json) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>("at.product-review.events", 0, 0L, key, json);
        r.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        r.headers().add(EventHeaders.EVENT_ID, eventId.toString().getBytes(StandardCharsets.UTF_8));
        r.headers().add(EventHeaders.OCCURRED_AT, "2026-10-09T12:00:00Z".getBytes(StandardCharsets.UTF_8));
        return r;
    }

    private void send(UUID reviewId, String type, String json) {
        listener.onMessage(rec(reviewId.toString(), type, UUID.randomUUID(), json));
    }

    private String item(UUID reviewId, String column) {
        return jdbc.sql("select " + column + "::text from moderation_item where booking_id = :b and status in "
                        + "('PENDING', 'ESCALATED')").param("b", reviewId).query(String.class).single();
    }

    private long items(UUID reviewId) {
        return jdbc.sql("select count(*) from moderation_item where booking_id = :b").param("b", reviewId)
                .query(Long.class).single();
    }

    private ModerationItemView view(UUID reviewId) {
        return moderation.queue(null, null, false, ModerationItemType.PRODUCT_REVIEW, 0, 200).items().stream()
                .filter(v -> v.item().subjectId().equals(reviewId)).findFirst().orElseThrow();
    }

    @Test
    void aSubmittedReviewIsQueuedOnceWithEverythingAModeratorNeeds() {
        UUID reviewId = UUID.randomUUID();
        String org = orgId();
        UUID eventId = UUID.randomUUID();
        String json = reviewEvent(reviewId, org, "PENDING", 2, "Call 0803 123 4567 or see www.shop.example", 0, null);

        listener.onMessage(rec(reviewId.toString(), "catalog.review.submitted", eventId, json));
        listener.onMessage(rec(reviewId.toString(), "catalog.review.submitted", eventId, json));
        assertThat(items(reviewId)).isEqualTo(1);
        assertThat(item(reviewId, "item_type")).isEqualTo("PRODUCT_REVIEW");
        assertThat(item(reviewId, "seller_org_id")).isEqualTo(org);
        assertThat(item(reviewId, "atin")).isEqualTo("AT7K2M9QXA");
        assertThat(item(reviewId, "service_name")).isEqualTo("Jollof Spice Mix 500g");
        assertThat(item(reviewId, "rating")).isEqualTo("2");
        assertThat(item(reviewId, "review_title")).isEqualTo("Not as hot as advertised");
        assertThat(item(reviewId, "review_photos")).contains("a.jpg").contains("b.jpg");
        assertThat(item(reviewId, "review_flags")).isEqualTo("PHONE,LINK");
        assertThat(item(reviewId, "customer_id")).isEqualTo("cust-77");
        assertThat(item(reviewId, "report_count")).isEqualTo("0");
        UPSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/ai/triage"))
                .withRequestBody(WireMock.matchingJsonPath("$.subjectType", WireMock.equalTo("REVIEW")))
                .withRequestBody(WireMock.matchingJsonPath("$.subjectId", WireMock.equalTo(reviewId.toString())))
                .withRequestBody(WireMock.matchingJsonPath("$.fields.productTitle",
                        WireMock.equalTo("Jollof Spice Mix 500g")))
                .withRequestBody(WireMock.notContaining("cust-77")));

        ModerationItemView v = view(reviewId);
        assertThat(v.item().type()).isEqualTo(ModerationItemType.PRODUCT_REVIEW);
        assertThat(v.item().product()).isNull();
        assertThat(v.item().orgId()).isEqualTo(org);
        assertThat(v.item().title()).isEqualTo("★★☆☆☆ review of Jollof Spice Mix 500g");
        assertThat(v.item().review().productReview()).isTrue();
        assertThat(v.item().review().photoUrls()).hasSize(2);
        assertThat(v.item().review().flags()).containsExactly("PHONE", "LINK");
        assertThat(outbox(reviewId.toString())).containsExactly("trustops.moderation.queued");
    }

    @Test
    void anEditedSubmissionReplacesTheOpenItem() {
        UUID reviewId = UUID.randomUUID();
        String org = orgId();
        send(reviewId, "catalog.review.submitted", reviewEvent(reviewId, org, "PENDING", 2, "First version", 0, null));
        send(reviewId, "catalog.review.submitted", reviewEvent(reviewId, org, "PENDING", 4, "Second version", 0, null));

        assertThat(items(reviewId)).isEqualTo(2);
        assertThat(item(reviewId, "review_text")).isEqualTo("Second version");
        assertThat(item(reviewId, "rating")).isEqualTo("4");
        assertThat(jdbc.sql("select count(*) from moderation_item where booking_id = :b and status = 'CLOSED' "
                        + "and decision_note like 'Superseded%'").param("b", reviewId).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void approvingPublishesTheReviewInCatalogWithTheServiceTokenAndIsAudited() {
        UUID reviewId = UUID.randomUUID();
        send(reviewId, "catalog.review.submitted", reviewEvent(reviewId, orgId(), "PENDING", 3, "ok", 0, null));
        ModerationItemView queued = view(reviewId);

        // catalog:moderate is enough: product reviews do not need the bookings permission
        TestSupport.as(TestSupport.trustSafety("amaka"));
        ModerationItemView approved = moderation.approve(queued.item().id(), "The number is the shop's");

        assertThat(approved.item().status()).isEqualTo(ModerationStatus.APPROVED);
        UPSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/product-reviews/" + reviewId + "/approve"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-trust-ops"))
                .withRequestBody(WireMock.matchingJsonPath("$.note", WireMock.equalTo("The number is the shop's"))));
        UPSTREAM.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/(reviews|products)/.*")));
        assertThat(jdbc.sql("select actor_id from audit_entry where action = 'trustops.review.approved' "
                        + "and entity_type = 'REVIEW' and entity_id = :id").param("id", reviewId.toString())
                .query(String.class).single()).isEqualTo("amaka");
        JsonNode decided = JSON.readTree(jdbc.sql("select payload::text from platform_outbox where aggregate_id = :id "
                        + "and type = 'trustops.moderation.decided'").param("id", reviewId.toString())
                .query(String.class).single());
        assertThat(decided.path("itemType").asString()).isEqualTo("PRODUCT_REVIEW");
        assertThat(decided.path("reviewId").asString()).isEqualTo(reviewId.toString());
        assertThat(decided.path("atin").asString()).isEqualTo("AT7K2M9QXA");
        assertThat(decided.path("rating").asInt()).isEqualTo(3);

        // catalog then publishes catalog.review.published: the item is already decided
        send(reviewId, "catalog.review.published", reviewEvent(reviewId, orgId(), "PUBLISHED", 3, "ok", 0, null));
        assertThat(moderation.get(queued.item().id()).item().status()).isEqualTo(ModerationStatus.APPROVED);
    }

    @Test
    void rejectingNeedsAReviewReasonAndRejectsThePendingReviewInCatalog() {
        UUID reviewId = UUID.randomUUID();
        send(reviewId, "catalog.review.submitted", reviewEvent(reviewId, orgId(), "PENDING", 1, "spam", 0, null));
        ModerationItemView queued = view(reviewId);
        TestSupport.as(TestSupport.trustSafety("amaka"));

        assertThatThrownBy(() -> moderation.reject(queued.item().id(), ModerationReasonCode.COUNTERFEIT, null))
                .isInstanceOf(TrustRuleViolation.class).hasMessageContaining("cannot remove a review");
        assertThatThrownBy(() -> moderation.reject(queued.item().id(), ModerationReasonCode.OTHER, " "))
                .isInstanceOf(TrustRuleViolation.class);
        UPSTREAM.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/product-reviews/.*")));

        ModerationItemView rejected = moderation.reject(queued.item().id(), ModerationReasonCode.SPAM,
                "Advertises another shop");
        assertThat(rejected.item().status()).isEqualTo(ModerationStatus.REJECTED);
        UPSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/product-reviews/" + reviewId + "/reject"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-trust-ops"))
                .withRequestBody(WireMock.matchingJsonPath("$.reasonCode", WireMock.equalTo("SPAM")))
                .withRequestBody(WireMock.matchingJsonPath("$.note", WireMock.equalTo("Advertises another shop"))));
        UPSTREAM.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/product-reviews/.*/remove")));
        assertThat(jdbc.sql("select count(*) from audit_entry where action = 'trustops.review.rejected' "
                + "and entity_id = :id").param("id", reviewId.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void reportsOnAPublishedReviewQueueItAndCountFurtherReports() {
        UUID reviewId = UUID.randomUUID();
        String org = orgId();
        send(reviewId, "catalog.review.reported", reviewEvent(reviewId, org, "PUBLISHED", 1, "Rude seller", 1,
                "ABUSIVE"));
        assertThat(item(reviewId, "item_type")).isEqualTo("PRODUCT_REVIEW");
        assertThat(item(reviewId, "report_count")).isEqualTo("1");
        assertThat(item(reviewId, "report_reason")).isEqualTo("ABUSIVE");

        send(reviewId, "catalog.review.reported", reviewEvent(reviewId, org, "PUBLISHED", 1, "Rude seller", 2, "SPAM"));
        assertThat(items(reviewId)).isEqualTo(1);
        assertThat(item(reviewId, "report_count")).isEqualTo("2");
        assertThat(item(reviewId, "report_reason")).as("the first report's reason is kept").isEqualTo("ABUSIVE");
        assertThat(view(reviewId).item().title()).isEqualTo("★☆☆☆☆ reported review of Jollof Spice Mix 500g");
    }

    @Test
    void allowingAReportedReviewKeepsItPublicWithoutCallingCatalog() {
        UUID reviewId = UUID.randomUUID();
        send(reviewId, "catalog.review.reported", reviewEvent(reviewId, orgId(), "PUBLISHED", 1, "Harsh but fair", 1,
                "ABUSIVE"));
        ModerationItemView queued = view(reviewId);
        TestSupport.as(TestSupport.trustSafety("amaka"));

        ModerationItemView kept = moderation.approve(queued.item().id(), "Within the rules");
        assertThat(kept.item().status()).isEqualTo(ModerationStatus.APPROVED);
        UPSTREAM.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/product-reviews/.*")));
        assertThat(jdbc.sql("select count(*) from audit_entry where action = 'trustops.review.kept' "
                + "and entity_id = :id").param("id", reviewId.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void removingAReportedReviewTakesItDownInCatalog() {
        UUID reviewId = UUID.randomUUID();
        send(reviewId, "catalog.review.reported", reviewEvent(reviewId, orgId(), "PUBLISHED", 1, "Insults", 1,
                "ABUSIVE"));
        ModerationItemView queued = view(reviewId);
        TestSupport.as(TestSupport.trustSafety("amaka"));

        moderation.reject(queued.item().id(), ModerationReasonCode.ABUSIVE, "Insults the seller");
        UPSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/product-reviews/" + reviewId + "/remove"))
                .withRequestBody(WireMock.matchingJsonPath("$.reasonCode", WireMock.equalTo("ABUSIVE"))));
        UPSTREAM.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/product-reviews/.*/reject")));
    }

    @Test
    void catalogRefusingKeepsTheItemOpenAndCatalogsOwnDecisionsCloseIt() {
        UUID reviewId = UUID.randomUUID();
        send(reviewId, "catalog.review.submitted", reviewEvent(reviewId, orgId(), "PENDING", 3, "ok", 0, null));
        ModerationItemView queued = view(reviewId);
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/product-reviews/[^/]+/approve"))
                .willReturn(WireMock.aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"catalog.review-deleted\",\"detail\":\"This review was deleted\"}")));
        TestSupport.as(TestSupport.trustSafety("amaka"));

        assertThatThrownBy(() -> moderation.approve(queued.item().id(), null)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("This review was deleted");
        assertThat(moderation.get(queued.item().id()).item().status()).isEqualTo(ModerationStatus.PENDING);

        // the customer deleted the review: catalog.review.deleted closes the item
        send(reviewId, "catalog.review.deleted", reviewEvent(reviewId, orgId(), "DELETED", 3, "ok", 0, null));
        ModerationItemView closed = moderation.get(queued.item().id());
        assertThat(closed.item().status()).isEqualTo(ModerationStatus.CLOSED);
        assertThat(closed.item().decidedBy()).isEqualTo("catalog");
        assertThat(closed.item().decisionNote()).isEqualTo("Decided in catalog: DELETED");
        assertThat(outbox(reviewId.toString())).endsWith("trustops.moderation.decided");
    }

    @Test
    void anEditRemovingTheProblemOrAStaffDecisionInCatalogClosesTheOpenItem() {
        String org = orgId();
        UUID edited = UUID.randomUUID();
        send(edited, "catalog.review.reported", reviewEvent(edited, org, "PUBLISHED", 2, "Rude", 1, "ABUSIVE"));
        send(edited, "catalog.review.updated", reviewEvent(edited, org, "PUBLISHED", 2, "Polite now", 0, null));
        assertThat(jdbc.sql("select status from moderation_item where booking_id = :b").param("b", edited)
                .query(String.class).single()).isEqualTo("CLOSED");

        UUID removed = UUID.randomUUID();
        send(removed, "catalog.review.reported", reviewEvent(removed, org, "PUBLISHED", 2, "Rude", 1, "ABUSIVE"));
        send(removed, "catalog.review.removed", reviewEvent(removed, org, "REMOVED", 2, "Rude", 1, null));
        assertThat(jdbc.sql("select status from moderation_item where booking_id = :b").param("b", removed)
                .query(String.class).single()).isEqualTo("CLOSED");

        UUID pending = UUID.randomUUID();
        send(pending, "catalog.review.submitted", reviewEvent(pending, org, "PENDING", 2, "Hmm", 0, null));
        send(pending, "catalog.review.published", reviewEvent(pending, org, "PUBLISHED", 2, "Hmm", 0, null));
        assertThat(jdbc.sql("select status from moderation_item where booking_id = :b").param("b", pending)
                .query(String.class).single()).isEqualTo("CLOSED");
    }

    @Test
    void otherEventsAndUnusableReviewsAreSkipped() {
        UUID reviewId = UUID.randomUUID();
        String org = orgId();
        // a reply, or states the event does not queue, add nothing
        send(reviewId, "catalog.review.replied", reviewEvent(reviewId, org, "PUBLISHED", 5, "Great", 0, null));
        send(reviewId, "catalog.review.submitted", reviewEvent(reviewId, org, "PUBLISHED", 5, "Great", 0, null));
        send(reviewId, "catalog.review.reported", reviewEvent(reviewId, org, "PENDING", 5, "Great", 1, "SPAM"));
        // malformed, invalid or incomplete payloads are logged and skipped
        listener.onMessage(rec("x", "catalog.review.submitted", UUID.randomUUID(), "not json"));
        send(reviewId, "catalog.review.submitted", reviewEvent(reviewId, org, "PENDING", 9, null, 0, null));
        send(reviewId, "catalog.review.submitted", "{\"reviewId\":\"" + reviewId + "\",\"rating\":4,\"status\":\"PENDING\"}");
        send(reviewId, "catalog.review.submitted", "{\"rating\":4}");
        ConsumerRecord<String, String> noId = new ConsumerRecord<>("at.product-review.events", 0, 0L,
                reviewId.toString(), reviewEvent(reviewId, org, "PENDING", 4, null, 0, null));
        noId.headers().add(EventHeaders.EVENT_TYPE, "catalog.review.submitted".getBytes(StandardCharsets.UTF_8));
        listener.onMessage(noId);
        assertThat(items(reviewId)).isZero();

        // a review without text or title is fine
        send(reviewId, "catalog.review.submitted", reviewEvent(reviewId, org, "PENDING", 4, null, 0, null));
        assertThat(item(reviewId, "status")).isEqualTo("PENDING");
        assertThat(jdbc.sql("select review_text is null from moderation_item where booking_id = :b")
                .param("b", reviewId).query(Boolean.class).single()).isTrue();
    }

    @Test
    void theQueueServesProductReviewsOverHttp() {
        UUID reviewId = UUID.randomUUID();
        String org = orgId();
        send(reviewId, "catalog.review.submitted", reviewEvent(reviewId, org, "PENDING", 2, "Call 0803 123 4567", 0,
                null));
        ModerationItemView queued = view(reviewId);

        JsonNode page = ok(send("GET", "/v1/moderation/items?type=PRODUCT_REVIEW&size=200", null));
        JsonNode item = null;
        for (JsonNode i : page.path("items")) {
            if (i.path("id").asString().equals(queued.item().id().toString())) {
                item = i;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.path("type").asString()).isEqualTo("PRODUCT_REVIEW");
        assertThat(item.path("productId").isNull()).isTrue();
        assertThat(item.path("atin").asString()).isEqualTo("AT7K2M9QXA");
        assertThat(item.path("sellerOrgId").asString()).isEqualTo(org);
        assertThat(item.path("review").path("bookingId").asString()).isEqualTo(reviewId.toString());
        assertThat(item.path("review").path("title").asString()).isEqualTo("Not as hot as advertised");
        assertThat(item.path("review").path("photoUrls")).hasSize(2);
        assertThat(item.path("review").path("flags").toString()).contains("PHONE", "LINK");
        assertThat(item.path("review").path("reports").asInt()).isZero();
        assertThat(item.path("review").path("reviewerName").asString()).isEqualTo("Kemi A.");

        JsonNode codes = ok(send("GET", "/v1/moderation/reason-codes?type=PRODUCT_REVIEW", null));
        assertThat(codes.findValuesAsString("code")).containsExactly("ABUSIVE", "PERSONAL_INFORMATION", "SPAM",
                "OFF_TOPIC", "CONFLICT_OF_INTEREST", "OTHER");
        problem(send("POST", "/v1/moderation/items/" + queued.item().id() + "/reject",
                Map.of("reasonCode", "WRONG_CATEGORY")), 422, "trustops.reason-code-not-applicable");
        JsonNode rejected = ok(send("POST", "/v1/moderation/items/" + queued.item().id() + "/reject",
                Map.of("reasonCode", "PERSONAL_INFORMATION", "note", "Phone number")));
        assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
    }
}
