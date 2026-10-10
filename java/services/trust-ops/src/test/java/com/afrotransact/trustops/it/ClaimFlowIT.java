package com.afrotransact.trustops.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.trustops.application.ClaimService;
import com.afrotransact.trustops.application.ClaimView;
import com.afrotransact.trustops.application.DecideClaimCommand;
import com.afrotransact.trustops.application.OpenClaimCommand;
import com.afrotransact.trustops.application.SlaMaintenanceService;
import com.afrotransact.trustops.domain.ClaimOutcome;
import com.afrotransact.trustops.domain.ClaimStatus;
import com.afrotransact.trustops.domain.ClaimType;
import com.afrotransact.trustops.port.PaymentsClient.RefundLine;
import com.afrotransact.trustops.port.UpstreamException;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** S118: claims with a seller response window, staff decisions with refunds through payments, notifications. */
class ClaimFlowIT extends TrustOpsIntegrationTest {

    private final ClaimService claims;
    private final SlaMaintenanceService slaSweep;

    @Autowired
    ClaimFlowIT(ClaimService claims, SlaMaintenanceService slaSweep) {
        this.claims = claims;
        this.slaSweep = slaSweep;
    }

    private ClaimView openAs(String customer, String seller) {
        TestSupport.as(TestSupport.customer(customer));
        ClaimView v = claims.open(new OpenClaimCommand(ClaimType.ITEM_NOT_RECEIVED, "ord-" + UUID.randomUUID(),
                "AT-2026-7KQ3MD", "shp-1", seller, null, 4_999, "USD", "Tracking says delivered, nothing arrived",
                List.of("asset_porch_photo")));
        TestSupport.reset();
        return v;
    }

    @Test
    void sellerRespondsStaffRefundsThroughPaymentsAndBothPartiesAreNotified() {
        String seller = orgId();
        ClaimView opened = openAs("cust-ada", seller);
        UUID id = opened.claim().id();
        assertThat(opened.claim().reference()).startsWith("AZ-");
        assertThat(opened.claim().customerId()).isEqualTo("cust-ada");
        assertThat(opened.claim().status()).isEqualTo(ClaimStatus.AWAITING_SELLER);
        assertThat(opened.sla().dueAt()).isEqualTo(opened.claim().openedAt().plus(Duration.ofHours(72)));

        // Another seller cannot answer; the claim's seller can.
        TestSupport.as(TestSupport.sellerSupport(orgId()));
        assertThatThrownBy(() -> claims.sellerRespond(id, "not ours", List.of()))
                .isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> claims.get(id)).isInstanceOf(ForbiddenActionException.class);
        TestSupport.as(TestSupport.sellerSupport(seller));
        ClaimView answered = claims.sellerRespond(id, "Handed to UPS, proof attached", List.of("asset_pod"));
        assertThat(answered.claim().status()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        assertThat(claims.forSeller(seller, null, 0, 10).items()).hasSize(1);

        TestSupport.as(TestSupport.customer("cust-ada"));
        assertThat(claims.addEvidence(id, "asset_empty_box", "What arrived").claim().evidence()).hasSize(3);
        assertThatThrownBy(() -> claims.decide(id, new DecideClaimCommand(ClaimOutcome.DENY, "no", null, null)))
                .isInstanceOf(ForbiddenActionException.class);

        TestSupport.as(TestSupport.trustSafety("sara"));
        ClaimView decided = claims.decide(id, new DecideClaimCommand(ClaimOutcome.REFUND, "Carrier lost the parcel",
                UUID.fromString("7a1c3f6e-2b4d-4c8e-9f00-123456789abc"),
                List.of(new RefundLine("line-1", 4_999, 0, 0))));
        assertThat(decided.claim().status()).isEqualTo(ClaimStatus.RESOLVED_REFUND);
        assertThat(decided.claim().refundMinor()).isEqualTo(4_999L);
        assertThat(decided.claim().refundId()).isNotBlank();
        assertThat(decided.sla()).isNull();
        UPSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo(
                        "/v1/payments/7a1c3f6e-2b4d-4c8e-9f00-123456789abc/refunds"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-trust-ops"))
                .withHeader("Idempotency-Key", WireMock.equalTo("trustops-claim-" + id))
                .withRequestBody(WireMock.matchingJsonPath("$.lines[0].lineId", WireMock.equalTo("line-1")))
                .withRequestBody(WireMock.matchingJsonPath("$.reason",
                        WireMock.containing(decided.claim().reference()))));

        List<String> recipients = jdbc.sql("select payload->'recipient'->>'id' from platform_outbox "
                        + "where aggregate_id = :id and type = 'notification.requested' "
                        + "and payload->>'templateKey' = 'claim.decided'")
                .param("id", id.toString()).query(String.class).list();
        assertThat(recipients).containsExactlyInAnyOrder("cust-ada", seller);
        assertThat(outbox(id.toString())).contains("trustops.claim.opened", "trustops.claim.seller_responded",
                "trustops.claim.decided");
        assertThat(jdbc.sql("select summary from audit_entry where action = 'trustops.claim.decided' "
                        + "and entity_id = :ref").param("ref", decided.claim().reference()).query(String.class)
                .single()).contains("RESOLVED_REFUND 4999 USD");
        assertThat(claims.history(id)).extracting(h -> h.action())
                .containsExactly("OPENED", "SELLER_RESPONDED", "EVIDENCE_ADDED", "DECIDED");
    }

    @Test
    void failedRefundLeavesTheClaimOpenForARetry() {
        String seller = orgId();
        ClaimView opened = openAs("cust-bisi", seller);
        TestSupport.as(TestSupport.trustSafety("sara"));
        claims.escalate(opened.claim().id(), "Seller unreachable by phone");
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/payments/[^/]+/refunds"))
                .willReturn(WireMock.serviceUnavailable()));
        DecideClaimCommand refund = new DecideClaimCommand(ClaimOutcome.REFUND, null, UUID.randomUUID(),
                List.of(new RefundLine("line-1", 2_000, 499, 0)));

        assertThatThrownBy(() -> claims.decide(opened.claim().id(), refund)).isInstanceOf(UpstreamException.class);
        assertThat(claims.get(opened.claim().id()).claim().status()).isEqualTo(ClaimStatus.ESCALATED);

        stubUpstreams();
        assertThat(claims.decide(opened.claim().id(), refund).claim().status())
                .isEqualTo(ClaimStatus.RESOLVED_REFUND);
    }

    @Test
    void refundAboveTheClaimIsRejectedBeforePaymentsIsCalled() {
        ClaimView opened = openAs("cust-chidi", orgId());
        TestSupport.as(TestSupport.trustSafety("sara"));
        claims.escalate(opened.claim().id(), "VIP");
        JsonNode p = problem(send("POST", "/v1/claims/" + opened.claim().id() + "/decision", Map.of("outcome",
                "REFUND", "paymentId", UUID.randomUUID().toString(), "refundLines", List.of(Map.of("lineId", "l1",
                        "itemsMinor", 6_000, "shippingMinor", 0, "taxMinor", 0)))), 422, "trustops.refund-amount");
        assertThat(p.path("fieldErrors").has("refund")).isTrue();
        UPSTREAM.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/payments/.*")));
    }

    @Test
    void lapsedSellerWindowMovesToReviewAndBreachedReviewEscalates() {
        ClaimView opened = openAs("cust-dayo", orgId());
        UUID id = opened.claim().id();

        TestSupport.CLOCK.advance(Duration.ofHours(73));
        assertThat(slaSweep.sweep().sellerWindowsLapsed()).isGreaterThanOrEqualTo(1);
        assertThat(claims.get(id).claim().status()).isEqualTo(ClaimStatus.UNDER_REVIEW);

        TestSupport.CLOCK.advance(Duration.ofDays(6));
        assertThat(slaSweep.sweep().claimsEscalated()).isGreaterThanOrEqualTo(1);
        ClaimView escalated = claims.get(id);
        assertThat(escalated.claim().status()).isEqualTo(ClaimStatus.ESCALATED);
        assertThat(escalated.claim().escalationReason()).startsWith("Decision SLA breached");

        TestSupport.as(TestSupport.trustSafety("sara"));
        ClaimView denied = claims.decide(id, new DecideClaimCommand(ClaimOutcome.DENY,
                "Signed proof of delivery", null, null));
        assertThat(denied.claim().status()).isEqualTo(ClaimStatus.RESOLVED_DENIED);
        assertThat(claims.history(id)).extracting(h -> h.action()).containsSubsequence("SELLER_WINDOW_LAPSED",
                "ESCALATED", "DECIDED");
    }

    @Test
    void httpOpenAndCustomerRules() {
        String seller = orgId();
        TestSupport.as(TestSupport.customer("cust-efe"));
        Map<String, Object> body = Map.of("type", "DAMAGED_ITEM", "orderId", "ord-http-" + UUID.randomUUID(),
                "sellerOrgId", seller, "amountMinor", 1_500, "currency", "USD", "description", "Jar arrived broken",
                "evidenceAssetIds", List.of("asset_jar"));
        JsonNode created = ok(send("POST", "/v1/claims", body), 201);
        assertThat(created.path("status").asString()).isEqualTo("AWAITING_SELLER");
        assertThat(created.path("sla").path("state").asString()).isEqualTo("ON_TRACK");
        problem(send("POST", "/v1/claims", body), 409, "trustops.claim-already-open");
        assertThat(ok(send("GET", "/v1/claims/mine", null)).path("total").asLong()).isEqualTo(1);

        problem(send("POST", "/v1/claims/" + created.path("id").asString() + "/decision",
                Map.of("outcome", "DENY", "note", "x")), 403, "auth.missing-permission");
        assertThat(ok(send("POST", "/v1/claims/" + created.path("id").asString() + "/withdraw", null))
                .path("status").asString()).isEqualTo("WITHDRAWN");

        TestSupport.as(TestSupport.sellerSupport(seller));
        problem(send("POST", "/v1/claims", body), 403, "trustops.customers-only");
    }
}
