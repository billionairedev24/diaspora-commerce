package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.seller.application.FoodServiceMigrationService;
import com.afrotransact.seller.application.SellingApprovalService;
import com.afrotransact.seller.application.SellingReviewService;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.port.StaffDirectory.StaffPerson;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Selling approvals on PostgreSQL: the SELL / APPLY / NOT_AVAILABLE answer, applying with documents, the staff case
 * (two-step review, who has it), disqualification, the lifecycle jobs (run twice: they act once), rule management and
 * the food service migration.
 */
class SellingApprovalsIT extends SellerIntegrationTest {

    private static final String REVIEW = "applications:review";
    private static final String APPROVE = "applications:approve";
    private static final String ASSIGN = "applications:assign";
    private static final String RULES = "selling-rules:manage";
    private final StaffPerson amina = new StaffPerson("sell-amina", "Amina Bello");
    private final StaffPerson adam = new StaffPerson("sell-adam", "Adam Ade");

    SellingApprovalService approvals;
    SellingReviewService sellingReview;
    FoodServiceMigrationService foodService;

    @BeforeEach
    void wire(@Autowired SellingApprovalService approvalService, @Autowired SellingReviewService reviewService,
              @Autowired FoodServiceMigrationService migration) {
        this.approvals = approvalService;
        this.sellingReview = reviewService;
        this.foodService = migration;
        TestActors.roster(REVIEW, amina);
        TestActors.roster(APPROVE, adam);
    }

    private String seller(String user) {
        return approvedSeller(user, "seller_growth", Cadence.MONTHLY);
    }

    private void asOwner(String user, String org) {
        TestActors.as(TestActors.member(user, org, "seller.owner"));
    }

    private JsonNode status(String org, String query) {
        Response r = get("/v1/orgs/" + org + "/selling-status?" + query);
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.body();
    }

    private static Map<String, Object> doc(String kind, LocalDate issued, LocalDate expires, boolean names) {
        Map<String, Object> d = new HashMap<>();
        d.put("kind", kind);
        d.put("assetId", "asset-" + kind.toLowerCase().replace('_', '-'));
        d.put("fileName", kind.toLowerCase() + ".pdf");
        if (issued != null) {
            d.put("issuedOn", issued.toString());
        }
        if (expires != null) {
            d.put("expiresOn", expires.toString());
        }
        d.put("namesAfroTransact", names);
        return d;
    }

    /** The documents dietary supplements ask for, valid today. */
    private List<Map<String, Object>> supplementDocs() {
        LocalDate future = LocalDate.now().plusDays(200);
        return List.of(doc("PERMIT_LICENCE", null, future, false), doc("SAFETY_CERTIFICATE", null, null, false),
                doc("LIABILITY_INSURANCE", null, future.plusDays(30), true));
    }

    private Response apply(String org, String scopeType, String key, List<Map<String, Object>> docs) {
        return post("/v1/orgs/" + org + "/selling-applications", Map.of("scopeType", scopeType, "scopeKey", key,
                "documents", docs));
    }

    private void review(String appId, String reviewer, String approver) {
        TestActors.as(TestActors.staff(reviewer, REVIEW));
        assertThat(post("/v1/selling-applications/" + appId + "/review", Map.of("action", "APPROVE",
                "reasonCode", "ALL_CHECKS_PASSED")).status()).isEqualTo(200);
        TestActors.as(TestActors.staff(approver, APPROVE));
        Response done = post("/v1/selling-applications/" + appId + "/decision", Map.of("action", "FINAL_APPROVE",
                "reasonCode", "ALL_CHECKS_PASSED"));
        assertThat(done.status()).as(done.body().toString()).isEqualTo(200);
    }

    @Test
    void openCategoriesSellAndGatedOnesNeedApprovalOrAreNotAvailable() {
        String user = unique("sell-a");
        String org = seller(user);
        asOwner(user, org);
        assertThat(status(org, "categoryCode=beans-pantry-sauces").path("level").asString()).isEqualTo("SELL");
        JsonNode supplements = status(org, "categoryCode=dietary-supplements");
        assertThat(supplements.path("level").asString()).isEqualTo("APPLY");
        assertThat(supplements.path("missing").get(0).path("state").asString()).isEqualTo("NOT_APPLIED");
        assertThat(supplements.path("missing").get(0).path("canApply").asBoolean()).isTrue();
        assertThat(supplements.path("reason").asString()).contains("Dietary supplements");
        JsonNode blocked = status(org, "categoryCode=gift-baskets-bundles");
        assertThat(blocked.path("level").asString()).isEqualTo("NOT_AVAILABLE");
        assertThat(blocked.path("reason").asString()).containsIgnoringCase("bundles");
        // gated brands: only inside the categories where the brand is the risk; designer brands everywhere
        assertThat(status(org, "categoryCode=adult-clothing-footwear&brand=Nike").path("level").asString())
                .isEqualTo("APPLY");
        assertThat(status(org, "categoryCode=decor-candles&brand=Nike").path("level").asString()).isEqualTo("SELL");
        assertThat(status(org, "categoryCode=decor-candles&brand=Gucci").path("level").asString()).isEqualTo("APPLY");
        assertThat(status(org, "categoryCode=adult-clothing-footwear&brand=Levis").path("level").asString())
                .isEqualTo("APPLY");
        // perfume is brand-gated: the category is open for everyone else
        assertThat(status(org, "categoryCode=perfumes-fragrances&brand=Handmade").path("level").asString())
                .isEqualTo("SELL");
        // AfroTransact Retail never needs approval
        TestActors.as(TestActors.staff("ops", REVIEW));
        assertThat(status("org_afrotransact_retail", "categoryCode=dietary-supplements").path("level").asString())
                .isEqualTo("SELL");
    }

    @Test
    void jewelryNeedsApprovalOnlyForGoldSilverGemstonesOrWatches() {
        String user = unique("sell-j");
        String org = seller(user);
        asOwner(user, org);
        assertThat(status(org, "categoryCode=jewelry-accessories&material=beads").path("level").asString())
                .isEqualTo("SELL");
        assertThat(status(org, "categoryCode=jewelry-accessories&material=18k%20Gold").path("level").asString())
                .isEqualTo("APPLY");
        assertThat(status(org, "categoryCode=jewelry-accessories&material=silver").path("level").asString())
                .isEqualTo("APPLY");
        assertThat(status(org, "categoryCode=jewelry-accessories&material=gemstone").path("level").asString())
                .isEqualTo("APPLY");
        assertThat(status(org, "categoryCode=jewelry-accessories&material=steel&watch=true").path("level")
                .asString()).isEqualTo("APPLY");
        JsonNode unknown = status(org, "categoryCode=jewelry-accessories");
        assertThat(unknown.path("level").asString()).isEqualTo("SELL");
        assertThat(unknown.path("pendingFacts").toString()).contains("material");
    }

    @Test
    void applyingNeedsTheRightDocumentsThenTwoPeopleApproveAndListingsMaySell() {
        String user = unique("sell-b");
        String org = seller(user);
        asOwner(user, org);
        // missing and wrong documents are named
        Response bad = apply(org, "CATEGORY", "dietary-supplements", List.of(doc("PERMIT_LICENCE", null,
                LocalDate.now().minusDays(1), false)));
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.body().toString()).contains("documents.SAFETY_CERTIFICATE").contains("documents.LIABILITY_INSURANCE")
                .contains("already expired");
        Response notNamed = apply(org, "CATEGORY", "dietary-supplements", List.of(
                doc("PERMIT_LICENCE", null, LocalDate.now().plusDays(100), false), doc("SAFETY_CERTIFICATE", null, null, false),
                doc("LIABILITY_INSURANCE", null, LocalDate.now().plusDays(100), false)));
        assertThat(notNamed.body().toString()).contains("additional insured");
        // a sell-anywhere scope has nothing to apply for
        assertThat(apply(org, "CATEGORY", "packaged-snacks", List.of()).status()).isEqualTo(422);

        Response ok = apply(org, "CATEGORY", "dietary-supplements", supplementDocs());
        assertThat(ok.status()).as(ok.body().toString()).isEqualTo(201);
        String appId = ok.text("/id");
        assertThat(ok.text("/status")).isEqualTo("PENDING_REVIEW");
        assertThat(ok.text("/statusWords")).isEqualTo("With our team");
        // a repeat while it is in progress returns the same application
        assertThat(apply(org, "CATEGORY", "dietary-supplements", supplementDocs()).text("/id")).isEqualTo(appId);
        JsonNode applyStatus = status(org, "categoryCode=dietary-supplements");
        assertThat(applyStatus.path("level").asString()).isEqualTo("APPLY");
        assertThat(applyStatus.path("missing").get(0).path("state").asString()).isEqualTo("APPLIED");
        assertThat(applyStatus.path("missing").get(0).path("canApply").asBoolean()).isFalse();

        // the case is in the staff queue like an application, with who has it
        TestActors.as(TestActors.staff(adam.id(), APPROVE));
        JsonNode row = null;
        for (JsonNode r : get("/v1/selling-applications?queue=AWAITING_REVIEW&size=200").body().path("items")) {
            if (appId.equals(r.path("id").asString())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("workflow").path("holder").asString()).isEqualTo("any reviewer");
        assertThat(row.path("scopeLabel").asString()).isEqualTo("Dietary supplements");

        // an approver cannot do step 1; the applicant cannot review their own; the reviewer cannot do step 2
        assertThat(post("/v1/selling-applications/" + appId + "/review", Map.of("action", "APPROVE",
                "reasonCode", "ALL_CHECKS_PASSED")).status()).isEqualTo(403);
        TestActors.as(TestActors.staff(user, REVIEW, APPROVE));
        assertThat(post("/v1/selling-applications/" + appId + "/review", Map.of("action", "APPROVE",
                "reasonCode", "ALL_CHECKS_PASSED")).status()).isEqualTo(403);
        TestActors.as(TestActors.staff(amina.id(), REVIEW, APPROVE));
        Response claimed = post("/v1/selling-applications/" + appId + "/assignment", Map.of("action", "CLAIM",
                "actorName", amina.name()));
        assertThat(claimed.text("/workflow/holder")).isEqualTo("with Amina Bello");
        assertThat(post("/v1/selling-applications/" + appId + "/review", Map.of("action", "APPROVE",
                "reasonCode", "ALL_CHECKS_PASSED", "actorName", amina.name())).status()).isEqualTo(200);
        assertThat(post("/v1/selling-applications/" + appId + "/decision", Map.of("action", "FINAL_APPROVE",
                "reasonCode", "ALL_CHECKS_PASSED")).status()).as("the reviewer cannot give final approval").isEqualTo(403);
        // the document viewer logs the view
        Response doc = get("/v1/selling-applications/" + appId + "/documents/asset-permit-licence");
        assertThat(doc.status()).as(doc.body().toString()).isEqualTo(200);
        TestActors.as(TestActors.staff(adam.id(), APPROVE));
        Response done = post("/v1/selling-applications/" + appId + "/decision", Map.of("action", "FINAL_APPROVE",
                "reasonCode", "ALL_CHECKS_PASSED", "actorName", adam.name()));
        assertThat(done.status()).as(done.body().toString()).isEqualTo(200);
        assertThat(done.text("/status")).isEqualTo("APPROVED");
        assertThat(done.body().path("timeline").toString()).contains("Amina Bello").contains("Adam Ade");
        assertThat(done.body().path("accessLog").size()).isEqualTo(1);
        assertThat(events("seller.selling.application.approved", appId)).isEqualTo(1);

        // now every listing in the scope may go live
        asOwner(user, org);
        assertThat(status(org, "categoryCode=dietary-supplements").path("level").asString()).isEqualTo("SELL");
        JsonNode page = get("/v1/orgs/" + org + "/selling-approvals").body();
        JsonNode row2 = rowOf(page, "dietary-supplements");
        assertThat(row2.path("state").asString()).isEqualTo("APPROVED");
        assertThat(row2.path("statusWords").asString()).isEqualTo("Approved");
        assertThat(row2.path("expiresAt").asString()).isNotBlank();
        assertThat(row2.path("onFile").size()).isEqualTo(3);
    }

    private static JsonNode rowOf(JsonNode page, String key) {
        for (JsonNode r : page.path("approvals")) {
            if (key.equals(r.path("scopeKey").asString())) {
                return r;
            }
        }
        throw new AssertionError("no row for " + key);
    }

    @Test
    void aReviewerCanReturnItAndTheSellerAnswersBySendingDocuments() {
        String user = unique("sell-c");
        String org = seller(user);
        asOwner(user, org);
        String appId = apply(org, "CATEGORY", "dietary-supplements", supplementDocs()).text("/id");
        TestActors.as(TestActors.staff(amina.id(), REVIEW));
        Response info = post("/v1/selling-applications/" + appId + "/review", Map.of("action", "REQUEST_INFO",
                "reasonCode", "DOCUMENT_UNREADABLE", "note", "The permit is cut off", "requestedItems",
                List.of("A full scan of the permit")));
        assertThat(info.status()).as(info.body().toString()).isEqualTo(200);
        asOwner(user, org);
        JsonNode mine = get("/v1/orgs/" + org + "/selling-applications/" + appId).body();
        assertThat(mine.path("statusWords").asString()).isEqualTo("Needs your attention");
        assertThat(mine.path("message").asString()).contains("A full scan of the permit").contains("cut off");
        JsonNode st = status(org, "categoryCode=dietary-supplements").path("missing").get(0);
        assertThat(st.path("state").asString()).isEqualTo("NEEDS_ATTENTION");
        assertThat(st.path("applicationStatus").asString()).isEqualTo("INFO_REQUESTED");
        Response again = post("/v1/orgs/" + org + "/selling-applications/" + appId + "/resubmit", Map.of("note",
                "Here is the full scan", "documents", supplementDocs()));
        assertThat(again.status()).as(again.body().toString()).isEqualTo(200);
        assertThat(again.text("/status")).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void cannotApplyIsFinalNeedsAReapplyDateAndNotAnAppeal() {
        String user = unique("sell-d");
        String org = seller(user);
        asOwner(user, org);
        String appId = apply(org, "CATEGORY", "dietary-supplements", supplementDocs()).text("/id");
        TestActors.as(TestActors.staff(amina.id(), REVIEW));
        post("/v1/selling-applications/" + appId + "/review", Map.of("action", "APPROVE", "reasonCode", "ALL_CHECKS_PASSED"));
        TestActors.as(TestActors.staff(adam.id(), APPROVE));
        Map<String, Object> body = new HashMap<>(Map.of("action", "DISQUALIFY", "reasonCode", "NOT_ELIGIBLE", "note",
                "Your account does not qualify for supplements"));
        assertThat(post("/v1/selling-applications/" + appId + "/decision", body).status())
                .as("a reapply date is required").isEqualTo(422);
        LocalDate reapply = LocalDate.now().plusDays(60);
        body.put("reapplyAfter", reapply.toString());
        Response done = post("/v1/selling-applications/" + appId + "/decision", body);
        assertThat(done.status()).as(done.body().toString()).isEqualTo(200);
        assertThat(done.text("/disqualified")).isEqualTo("true");
        assertThat(done.text("/status")).isEqualTo("REJECTED");
        asOwner(user, org);
        JsonNode s = status(org, "categoryCode=dietary-supplements");
        assertThat(s.path("level").asString()).isEqualTo("NOT_AVAILABLE");
        assertThat(s.path("reason").asString()).contains("cannot be appealed").contains("does not qualify");
        Response retry = apply(org, "CATEGORY", "dietary-supplements", supplementDocs());
        assertThat(retry.status()).isEqualTo(409);
        // after the date the seller can apply again
        jdbc.sql("update selling_approval set reapply_after = :d where org_id = :o").param("d", LocalDate.now().minusDays(1))
                .param("o", org).update();
        assertThat(status(org, "categoryCode=dietary-supplements").path("level").asString()).isEqualTo("APPLY");
        assertThat(apply(org, "CATEGORY", "dietary-supplements", supplementDocs()).status()).isEqualTo(201);
    }

    @Test
    void lifecycleWarnsThenExpiresThenPausesAndRenewalRestoresEachOnceEvenWhenRunTwice() {
        String user = unique("sell-e");
        String org = seller(user);
        asOwner(user, org);
        String appId = apply(org, "CATEGORY", "dietary-supplements", supplementDocs()).text("/id");
        review(appId, amina.id(), adam.id());
        String approvalId = jdbc.sql("select id::text from selling_approval where org_id = :o").param("o", org)
                .query(String.class).single();
        // 25 days left: the 30-day warning, once
        setExpiry(approvalId, Instant.now().plus(Duration.ofDays(25)));
        approvals.runLifecycle(Instant.now());
        approvals.runLifecycle(Instant.now());
        assertThat(events("seller.selling.approval.expiry_warning", org)).isEqualTo(1);
        // 6 days left: the 7-day warning (the 14-day one is skipped, not sent late)
        setExpiry(approvalId, Instant.now().plus(Duration.ofDays(6)));
        approvals.runLifecycle(Instant.now());
        approvals.runLifecycle(Instant.now());
        assertThat(events("seller.selling.approval.expiry_warning", org)).isEqualTo(2);
        assertThat(lastEvent("seller.selling.approval.expiry_warning", org).path("daysLeft").asInt()).isBetween(5, 6);
        asOwner(user, org);
        assertThat(get("/v1/orgs/" + org + "/selling-approvals").body().path("notices").toString())
                .contains("runs out in");
        // expired: new listings are refused, existing ones stay up during the 14 days of grace
        setExpiry(approvalId, Instant.now().minus(Duration.ofDays(3)));
        approvals.runLifecycle(Instant.now());
        approvals.runLifecycle(Instant.now());
        assertThat(events("seller.selling.approval.expired", org)).isEqualTo(1);
        assertThat(events("seller.selling.listings.paused", org)).isZero();
        assertThat(status(org, "categoryCode=dietary-supplements").path("missing").get(0).path("state").asString())
                .isEqualTo("EXPIRED");
        // grace over: the scope's listings are paused, once
        setExpiry(approvalId, Instant.now().minus(Duration.ofDays(15)));
        approvals.runLifecycle(Instant.now());
        approvals.runLifecycle(Instant.now());
        assertThat(events("seller.selling.listings.paused", org)).isEqualTo(1);
        JsonNode paused = lastEvent("seller.selling.listings.paused", org);
        assertThat(paused.path("scopeKey").asString()).isEqualTo("dietary-supplements");
        assertThat(paused.path("reason").asString()).isEqualTo("EXPIRED");
        assertThat(paused.path("recipientUserIds").toString()).contains(user);
        // renewal: a new application, approved again, restores the listings
        String renewal = apply(org, "CATEGORY", "dietary-supplements", supplementDocs()).text("/id");
        review(renewal, amina.id(), adam.id());
        assertThat(events("seller.selling.listings.restored", org)).isEqualTo(1);
        asOwner(user, org);
        assertThat(status(org, "categoryCode=dietary-supplements").path("level").asString()).isEqualTo("SELL");
        approvals.runLifecycle(Instant.now());
        assertThat(events("seller.selling.listings.paused", org)).isEqualTo(1);
    }

    private void setExpiry(String approvalId, Instant at) {
        jdbc.sql("update selling_approval set expires_at = :t, status = 'APPROVED', listings_paused = false "
                + "where id = cast(:id as uuid)").param("t", Timestamp.from(at)).param("id", approvalId).update();
    }

    @Test
    void staffCanPauseAndRestoreAnApprovalForAPerformanceProblem() {
        String user = unique("sell-f");
        String org = seller(user);
        asOwner(user, org);
        String appId = apply(org, "CATEGORY", "dietary-supplements", supplementDocs()).text("/id");
        review(appId, amina.id(), adam.id());
        String approvalId = jdbc.sql("select id::text from selling_approval where org_id = :o").param("o", org)
                .query(String.class).single();
        TestActors.as(TestActors.staff(adam.id(), APPROVE));
        assertThat(post("/v1/selling-approvals/" + approvalId + "/pause", Map.of("reason", "short")).status())
                .isEqualTo(422);
        Response paused = post("/v1/selling-approvals/" + approvalId + "/pause", Map.of("reason",
                "Too many late shipments this month", "actorName", adam.name()));
        assertThat(paused.status()).as(paused.body().toString()).isEqualTo(200);
        assertThat(paused.text("/listingsPaused")).isEqualTo("true");
        assertThat(events("seller.selling.listings.paused", org)).isEqualTo(1);
        asOwner(user, org);
        JsonNode s = status(org, "categoryCode=dietary-supplements");
        assertThat(s.path("level").asString()).isEqualTo("NOT_AVAILABLE");
        assertThat(s.path("reason").asString()).contains("paused").contains("late shipments");
        TestActors.as(TestActors.staff(adam.id(), APPROVE));
        assertThat(post("/v1/selling-approvals/" + approvalId + "/restore", Map.of("reason",
                "Performance is back to normal")).status()).isEqualTo(200);
        assertThat(events("seller.selling.listings.restored", org)).isEqualTo(1);
        asOwner(user, org);
        assertThat(status(org, "categoryCode=dietary-supplements").path("level").asString()).isEqualTo("SELL");
    }

    @Test
    void staffManageRulesWithAReasonAndAnAuditTrail() {
        String brand = "Zed" + unique("x").replace("-", "");
        TestActors.as(TestActors.staff("rules-viewer", REVIEW));
        assertThat(get("/v1/selling-rules?scopeType=BRAND_IN_CATEGORY&text=nike").body().path("total").asInt())
                .isGreaterThan(0);
        Map<String, Object> input = new HashMap<>(Map.of("scopeType", "BRAND", "key", brand, "level", "APPLY",
                "documents", List.of(Map.of("kind", "BRAND_AUTHORIZATION", "optional", false)),
                "reason", "A brand we gate", "actorName", "Rita Rules"));
        Response denied = post("/v1/selling-rules", input);
        assertThat(denied.status()).as("view-only staff cannot change rules: " + denied.body()).isEqualTo(403);
        TestActors.as(TestActors.staff("rules-admin", RULES));
        input.put("reason", "short");
        assertThat(post("/v1/selling-rules", input).status()).isEqualTo(422);
        input.put("reason", "Counterfeits reported on this brand");
        Response created = post("/v1/selling-rules", input);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        String id = created.text("/id");
        assertThat(created.text("/source")).isEqualTo("STAFF");
        assertThat(post("/v1/selling-rules", input).status()).as("one rule per scope").isEqualTo(409);
        // sellers now need approval for that brand, everywhere
        String user = unique("sell-g");
        String org = seller(user);
        asOwner(user, org);
        assertThat(status(org, "categoryCode=packaged-snacks&brand=" + brand).path("level").asString()).isEqualTo("APPLY");
        // edit: not available, with the reason sellers read
        TestActors.as(TestActors.staff("rules-admin", RULES));
        Map<String, Object> edit = new HashMap<>(Map.of("scopeType", "BRAND", "key", brand, "level", "NOT_AVAILABLE",
                "sellerReason", "We cannot verify this brand yet", "reason", "Brand owner asked us to pause it",
                "version", created.body().path("version").asLong()));
        Response edited = put("/v1/selling-rules/" + id, edit);
        assertThat(edited.status()).as(edited.body().toString()).isEqualTo(200);
        assertThat(put("/v1/selling-rules/" + id, edit).status()).as("stale version").isEqualTo(409);
        asOwner(user, org);
        JsonNode blocked = status(org, "categoryCode=packaged-snacks&brand=" + brand);
        assertThat(blocked.path("level").asString()).isEqualTo("NOT_AVAILABLE");
        assertThat(blocked.path("reason").asString()).contains("cannot verify");
        TestActors.as(TestActors.staff("rules-viewer", REVIEW));
        JsonNode audit = get("/v1/selling-rules/" + id + "/audit").body();
        assertThat(audit.size()).isEqualTo(2);
        assertThat(audit.get(0).path("reason").asString()).isEqualTo("Brand owner asked us to pause it");
        assertThat(events("seller.selling.rule.changed", id)).isEqualTo(2);
    }

    @Test
    void bakeryAnswersBecomePendingFoodServiceApplicationsAndNeverAutoApprove() {
        String user = unique("sell-h");
        String org = seller(user);
        StubMapping stub = STRIPE.stubFor(WireMock.get("/v1/internal/bakery-claims").willReturn(WireMock.okJson(
                "{\"organisations\":[\"" + org + "\",\"org_unknown_" + user + "\"]}")));
        try {
            assertThat(foodService.run()).isEqualTo(1);
            assertThat(foodService.run()).as("a re-run creates nothing").isZero();
        } finally {
            STRIPE.removeStub(stub);
        }
        TestActors.as(TestActors.staff(amina.id(), REVIEW));
        String appId = null;
        for (JsonNode r : get("/v1/selling-applications?queue=AWAITING_REVIEW&size=200").body().path("items")) {
            if (org.equals(r.path("orgId").asString())) {
                appId = r.path("id").asString();
                assertThat(r.path("source").asString()).isEqualTo("MIGRATION");
                assertThat(r.path("scopeKey").asString()).isEqualTo("FOOD_SERVICE");
            }
        }
        assertThat(appId).isNotNull();
        // no permit on file: step 1 cannot approve it
        Response approve = post("/v1/selling-applications/" + appId + "/review", Map.of("action", "APPROVE",
                "reasonCode", "ALL_CHECKS_PASSED"));
        assertThat(approve.status()).isEqualTo(422);
        assertThat(approve.body().toString()).contains("documents.PERMIT_LICENCE");
        asOwner(user, org);
        assertThat(get("/v1/orgs/" + org + "/selling-capabilities").text("/foodService/approved")).isEqualTo("false");
        assertThat(status(org, "categoryCode=bakery-products").path("missing").get(0).path("scope").path("key")
                .asString()).as("one approval covers every food service category").isEqualTo("FOOD_SERVICE");
        // the seller's real application is the migrated one; once approved the capability opens
        Response mine = apply(org, "CAPABILITY", "FOOD_SERVICE", List.of());
        assertThat(mine.text("/id")).isEqualTo(appId);
        TestActors.as(TestActors.staff(amina.id(), REVIEW));
        post("/v1/selling-applications/" + appId + "/review", Map.of("action", "REQUEST_INFO", "reasonCode",
                "PERMIT_MISSING", "note", "Please upload your food permit", "requestedItems", List.of("Food permit",
                "Insurance")));
        asOwner(user, org);
        LocalDate future = LocalDate.now().plusDays(300);
        Response sent = post("/v1/orgs/" + org + "/selling-applications/" + appId + "/resubmit", Map.of("documents",
                List.of(doc("PERMIT_LICENCE", null, future, false), doc("LIABILITY_INSURANCE", null, future, true))));
        assertThat(sent.status()).as(sent.body().toString()).isEqualTo(200);
        review(appId, amina.id(), adam.id());
        asOwner(user, org);
        assertThat(get("/v1/orgs/" + org + "/selling-capabilities").text("/foodService/approved")).isEqualTo("true");
        assertThat(status(org, "categoryCode=bakery-products").path("level").asString()).isEqualTo("SELL");
    }

    @Test
    void escalationAppliesToSellingCasesLikeApplicationsAndRunsOnce() {
        String user = unique("sell-i");
        String org = seller(user);
        asOwner(user, org);
        String appId = apply(org, "CATEGORY", "dietary-supplements", supplementDocs()).text("/id");
        TestActors.roster(ASSIGN, new StaffPerson("sell-sue", "Sue Supervisor"));
        TestActors.as(TestActors.staff("sell-sue", ASSIGN));
        Map<String, Object> settings = new HashMap<>();
        settings.put("reviewSlaHours", 24);
        settings.put("approvalSlaHours", 24);
        settings.put("dueSoonPercent", 75);
        settings.put("unassignedAfterHours", 4);
        settings.put("level2AfterHours", 24);
        settings.put("autoAssign", "NONE");
        settings.put("enforceAssignee", false);
        settings.put("sendBackToReviewer", false);
        settings.put("reviewChain", Map.of("level1", Map.of("userIds", List.of("sell-sue")), "level2", Map.of()));
        settings.put("approvalChain", Map.of("level1", Map.of(), "level2", Map.of()));
        settings.put("reason", "Set the chain for selling approvals");
        assertThat(put("/v1/application-review-settings", settings).status()).isEqualTo(200);
        jdbc.sql("update selling_case set stage_entered_at = :t where application_id = :a")
                .param("t", Timestamp.from(Instant.now().minus(Duration.ofHours(30)))).param("a", appId).update();
        List<Integer> levels = new ArrayList<>();
        sellingReview.runDueEscalations();
        sellingReview.runDueEscalations();
        JsonNode detail = get("/v1/selling-applications/" + appId).body();
        levels.add(detail.path("workflow").path("escalationLevel").asInt());
        assertThat(levels).containsExactly(1);
        assertThat(detail.path("workflow").path("slaState").asString()).isEqualTo("OVERDUE");
        assertThat(detail.path("workflow").path("escalatedTo").toString()).contains("Sue Supervisor");
        assertThat(events("seller.application.escalated", appId)).isEqualTo(1);
        settings.put("reviewChain", Map.of("level1", Map.of(), "level2", Map.of()));
        settings.put("reason", "Back to the default chain");
        put("/v1/application-review-settings", settings);
    }
}
