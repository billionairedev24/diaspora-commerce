package com.afrotransact.trustops.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.trustops.application.ModerationItemView;
import com.afrotransact.trustops.application.ModerationService;
import com.afrotransact.trustops.application.SlaMaintenanceService;
import com.afrotransact.trustops.domain.ListingFlagSource;
import com.afrotransact.trustops.domain.ListingReport;
import com.afrotransact.trustops.domain.ModerationItemType;
import com.afrotransact.trustops.domain.ModerationPriority;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.domain.ModerationStatus;
import com.afrotransact.trustops.domain.ProductSubmission;
import com.afrotransact.trustops.domain.SlaState;
import com.afrotransact.trustops.domain.TriageStatus;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** S117: queue from catalog submissions, AI hints (fail-open), assignment, decisions through catalog, SLA escalation. */
class ModerationFlowIT extends TrustOpsIntegrationTest {

    private final ModerationService moderation;
    private final SlaMaintenanceService slaSweep;

    @Autowired
    ModerationFlowIT(ModerationService moderation, SlaMaintenanceService slaSweep) {
        this.moderation = moderation;
        this.slaSweep = slaSweep;
    }

    private ModerationItemView enqueue(String title) {
        ProductSubmission p = new ProductSubmission(UUID.randomUUID(), "AT0000" + title.length(), orgId(), "SELLER",
                title, "herbal");
        return moderation.enqueue(UUID.randomUUID(), p).orElseThrow();
    }

    @Test
    void highRiskHintThenAssignAndRejectThroughCatalogIsAudited() {
        ModerationItemView queued = enqueue("Bitter kola cures diabetes");
        UUID productId = queued.item().product().productId();

        assertThat(queued.item().triageStatus()).isEqualTo(TriageStatus.COMPLETED);
        assertThat(queued.item().triage().flags()).singleElement()
                .satisfies(f -> assertThat(f.citedFields()).containsExactly("title"));
        assertThat(queued.item().priority()).isEqualTo(ModerationPriority.HIGH);
        assertThat(queued.sla().state()).isEqualTo(SlaState.ON_TRACK);
        UPSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/ai/triage"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-trust-ops"))
                .withRequestBody(WireMock.matchingJsonPath("$.subjectType", WireMock.equalTo("LISTING")))
                .withRequestBody(WireMock.matchingJsonPath("$.fields.title",
                        WireMock.equalTo("Bitter kola cures diabetes"))));

        TestSupport.as(TestSupport.trustSafety("sara"));
        assertThat(moderation.assign(queued.item().id(), null).item().assignee()).isEqualTo("sara");
        assertThat(moderation.queue(null, null, true, null, 0, 50).items())
                .anySatisfy(v -> assertThat(v.item().id()).isEqualTo(queued.item().id()));

        ModerationItemView rejected = moderation.reject(queued.item().id(),
                ModerationReasonCode.UNAPPROVED_HEALTH_CLAIM, "Remove the cure claim");
        assertThat(rejected.item().status()).isEqualTo(ModerationStatus.REJECTED);
        assertThat(rejected.item().decidedBy()).isEqualTo("sara");
        UPSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/products/" + productId + "/reject"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-trust-ops"))
                .withRequestBody(WireMock.matchingJsonPath("$.note", WireMock.equalTo(
                        "[UNAPPROVED_HEALTH_CLAIM] Unapproved health or medical claim: Remove the cure claim"))));

        assertThat(jdbc.sql("select actor_id from audit_entry where action = 'trustops.listing.rejected' "
                + "and entity_id = :id").param("id", productId.toString()).query(String.class).single())
                .isEqualTo("sara");
        assertThat(outbox(productId.toString())).contains("trustops.moderation.queued",
                "trustops.moderation.decided");
        assertThat(outbox("audit-log")).contains("trustops.audit.recorded");

        // Catalog's own approved/rejected event for the same product is ignored once decided here.
        moderation.closeFromCatalog(UUID.randomUUID(), productId, "REJECTED");
        assertThat(moderation.get(queued.item().id()).item().status()).isEqualTo(ModerationStatus.REJECTED);
    }

    @Test
    void aiOutageDoesNotBlockTheQueue() {
        UPSTREAM.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/ai/triage"))
                .willReturn(WireMock.serverError().withBody("{\"code\":\"ai.unavailable\"}")));

        ModerationItemView queued = enqueue("Kente stole, handwoven");
        assertThat(queued.item().triageStatus()).isEqualTo(TriageStatus.UNAVAILABLE);
        assertThat(queued.item().priority()).isEqualTo(ModerationPriority.NORMAL);
        assertThat(queued.sla().dueAt()).isEqualTo(queued.item().enqueuedAt().plus(Duration.ofHours(24)));

        ModerationItemView approved = moderation.approve(queued.item().id(), "Looks fine");
        assertThat(approved.item().status()).isEqualTo(ModerationStatus.APPROVED);
        UPSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/products/"
                + queued.item().product().productId() + "/approve")));
    }

    @Test
    void redeliveryAndDuplicateSubmissionsAreIgnored() {
        UUID eventId = UUID.randomUUID();
        ProductSubmission p = new ProductSubmission(UUID.randomUUID(), "AT00000001", orgId(), "SELLER",
                "Adire maxi dress", "dresses");
        assertThat(moderation.enqueue(eventId, p)).isPresent();
        assertThat(moderation.enqueue(eventId, p)).isEmpty();
        assertThat(moderation.enqueue(UUID.randomUUID(), p)).isEmpty();
    }

    @Test
    void catalogRefusalKeepsTheItemOpen() {
        ModerationItemView queued = enqueue("Smoked fish 2 lb");
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/products/[^/]+/approve"))
                .willReturn(WireMock.aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"catalog.invalid-transition\",\"detail\":\"Product is LIVE\"}")));

        assertThatThrownBy(() -> moderation.approve(queued.item().id(), null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Product is LIVE");
        assertThat(moderation.get(queued.item().id()).item().status()).isEqualTo(ModerationStatus.PENDING);

        moderation.closeFromCatalog(UUID.randomUUID(), queued.item().product().productId(), "LIVE");
        assertThat(moderation.get(queued.item().id()).item().status()).isEqualTo(ModerationStatus.CLOSED);
    }

    @Test
    void breachedSlaEscalatesAutomaticallyAndEscalationHasItsOwnTimer() {
        UPSTREAM.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/ai/triage"))
                .willReturn(WireMock.okJson("{\"riskLevel\":\"LOW\",\"summary\":\"ok\",\"flags\":[]}")));
        ModerationItemView queued = enqueue("Ogiri fermented seasoning");
        assertThat(queued.item().priority()).isEqualTo(ModerationPriority.NORMAL);

        TestSupport.CLOCK.advance(Duration.ofHours(19));
        assertThat(moderation.get(queued.item().id()).sla().state()).isEqualTo(SlaState.AT_RISK);
        TestSupport.CLOCK.advance(Duration.ofHours(6));
        assertThat(moderation.get(queued.item().id()).sla().state()).isEqualTo(SlaState.BREACHED);

        assertThat(slaSweep.sweep().listingsEscalated()).isGreaterThanOrEqualTo(1);
        ModerationItemView escalated = moderation.get(queued.item().id());
        assertThat(escalated.item().status()).isEqualTo(ModerationStatus.ESCALATED);
        assertThat(escalated.item().escalationReason()).startsWith("SLA breached");
        assertThat(escalated.sla().state()).isEqualTo(SlaState.ON_TRACK);
        assertThat(jdbc.sql("select actor_id from audit_entry where action = 'trustops.listing.escalated' "
                        + "and entity_id = :id").param("id", queued.item().product().productId().toString())
                .query(String.class).single()).isEqualTo("system");
    }

    @Test
    void httpQueueNeedsModeratePermission() {
        enqueue("Designer-style wax print bag");
        JsonNode page = ok(send("GET", "/v1/moderation/items?size=5", null));
        assertThat(page.path("items").size()).isPositive();
        assertThat(page.path("items").get(0).path("sla").path("state").asString()).isNotEmpty();
        assertThat(ok(send("GET", "/v1/moderation/reason-codes", null)).size()).isEqualTo(
                ModerationReasonCode.values().length);

        TestSupport.as(TestSupport.marketing());
        problem(send("GET", "/v1/moderation/items", null), 403, "auth.missing-permission");
        assertThatThrownBy(() -> moderation.queue(null, null, false, null, 0, 10))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void rejectWithoutReasonCodeIsAValidationError() {
        ModerationItemView queued = enqueue("Skin-lightening cream");
        JsonNode p = ok(send("POST", "/v1/moderation/items/" + queued.item().id() + "/reject",
                Map.of("note", "x")), 400);
        assertThat(p.path("errors").toString()).contains("reasonCode");
    }

    @Test
    void flaggedLiveListingsAreCountedKeptOrTakenDownInCatalog() {
        ProductSubmission listing = new ProductSubmission(UUID.randomUUID(), "AT00LIVE01", orgId(), "SELLER",
                "Shea butter that heals eczema", "skincare");
        ListingReport report = new ListingReport(UUID.randomUUID(), ListingFlagSource.CUSTOMER_REPORT,
                ModerationReasonCode.UNAPPROVED_HEALTH_CLAIM, "Claims to heal eczema", "cust-1", null, 1);
        ModerationItemView queued = moderation.enqueueLiveListing(UUID.randomUUID(), listing, report).orElseThrow();
        assertThat(queued.item().type()).isEqualTo(ModerationItemType.LIVE_LISTING);
        assertThat(queued.item().report().reports()).isEqualTo(1);
        UPSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/ai/triage"))
                .withRequestBody(WireMock.matchingJsonPath("$.fields.reportReason",
                        WireMock.equalTo("UNAPPROVED_HEALTH_CLAIM"))));

        // A second report on the same listing is counted on the open item, not queued again.
        ModerationItemView counted = moderation.enqueueLiveListing(UUID.randomUUID(), listing, new ListingReport(
                UUID.randomUUID(), ListingFlagSource.AUTOMATED, ModerationReasonCode.UNAPPROVED_HEALTH_CLAIM, null,
                "ai-gateway", "HIGH", 1)).orElseThrow();
        assertThat(counted.item().id()).isEqualTo(queued.item().id());
        assertThat(counted.item().report().reports()).isEqualTo(2);
        assertThat(counted.item().report().source()).isEqualTo(ListingFlagSource.CUSTOMER_REPORT);

        TestSupport.as(TestSupport.trustSafety("sara"));
        assertThat(moderation.queue(null, null, false, ModerationItemType.LIVE_LISTING, 0, 200).items())
                .allSatisfy(v -> assertThat(v.item().type()).isEqualTo(ModerationItemType.LIVE_LISTING))
                .anySatisfy(v -> assertThat(v.item().id()).isEqualTo(queued.item().id()));
        assertThat(moderation.queue(null, null, false, ModerationItemType.NEW_LISTING, 0, 200).items())
                .noneSatisfy(v -> assertThat(v.item().id()).isEqualTo(queued.item().id()));

        ModerationItemView removed = moderation.reject(queued.item().id(), ModerationReasonCode.UNAPPROVED_HEALTH_CLAIM,
                "Medical claims on a cosmetic");
        assertThat(removed.item().status()).isEqualTo(ModerationStatus.REJECTED);
        UPSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/products/" + listing.productId()
                        + "/takedown"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-trust-ops"))
                .withRequestBody(WireMock.matchingJsonPath("$.reasonCode", WireMock.equalTo("UNAPPROVED_HEALTH_CLAIM")))
                .withRequestBody(WireMock.matchingJsonPath("$.note", WireMock.equalTo("Medical claims on a cosmetic"))));
        UPSTREAM.verify(0, WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/products/" + listing.productId()
                + "/reject")));
        assertThat(jdbc.sql("select count(*) from audit_entry where action = 'trustops.listing.taken_down' "
                + "and entity_id = :id").param("id", listing.productId().toString()).query(Long.class).single())
                .isEqualTo(1);

        // Allowing a flagged live listing keeps it live without touching catalog.
        ProductSubmission other = new ProductSubmission(UUID.randomUUID(), "AT00LIVE02", orgId(), "SELLER",
                "Ankara tote bag", "bags");
        ModerationItemView second = moderation.enqueueLiveListing(UUID.randomUUID(), other, new ListingReport(
                UUID.randomUUID(), ListingFlagSource.STAFF, ModerationReasonCode.IMAGE_POLICY, null, "staff-1", null,
                1)).orElseThrow();
        ModerationItemView kept = moderation.approve(second.item().id(), "Photos are the seller's own");
        assertThat(kept.item().status()).isEqualTo(ModerationStatus.APPROVED);
        UPSTREAM.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/products/" + other.productId()
                + "/.*")));
    }

    @Test
    void takedownRefusedByCatalogKeepsTheLiveItemOpen() {
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/products/[^/]+/takedown"))
                .willReturn(WireMock.aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":\"catalog.invalid-transition\"}")));
        ProductSubmission listing = new ProductSubmission(UUID.randomUUID(), "AT00LIVE03", orgId(), "SELLER",
                "Kente stole", "fashion");
        ModerationItemView queued = moderation.enqueueLiveListing(UUID.randomUUID(), listing, new ListingReport(
                UUID.randomUUID(), ListingFlagSource.CUSTOMER_REPORT, ModerationReasonCode.COUNTERFEIT, null, "c",
                null, 1)).orElseThrow();
        TestSupport.as(TestSupport.trustSafety("sara"));
        assertThatThrownBy(() -> moderation.reject(queued.item().id(), ModerationReasonCode.COUNTERFEIT, null))
                .isInstanceOf(ConflictException.class);
        assertThat(moderation.get(queued.item().id()).item().open()).isTrue();

        // Staff took it down directly in catalog: catalog.product.taken_down closes the item.
        moderation.closeFromCatalog(UUID.randomUUID(), listing.productId(), "TAKEN_DOWN");
        assertThat(moderation.get(queued.item().id()).item().status()).isEqualTo(ModerationStatus.CLOSED);
    }
}
