package com.afrotransact.trustops.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.trustops.application.SellerEnforcementService;
import com.afrotransact.trustops.application.SellerHealthService;
import com.afrotransact.trustops.application.SellerHealthView;
import com.afrotransact.trustops.domain.EnforcementStatus;
import com.afrotransact.trustops.domain.SellerEnforcement;
import com.afrotransact.trustops.domain.SellerHealthReport;
import com.afrotransact.trustops.domain.SellerHealthStatus;
import com.afrotransact.trustops.domain.SuspensionReason;
import com.afrotransact.trustops.port.ShipmentFact;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** S120 / S223: metrics from orders events, push to offers, suspend and reinstate through seller and offers. */
class SellerHealthIT extends TrustOpsIntegrationTest {

    private final SellerHealthService health;
    private final SellerEnforcementService enforcement;

    @Autowired
    SellerHealthIT(SellerHealthService health, SellerEnforcementService enforcement) {
        this.health = health;
        this.enforcement = enforcement;
    }

    /** 20 shipments placed 10 days ago, promised in 3 days: 10 delivered on time, 2 late, 1 cancelled by the seller. */
    private void history(String org) {
        Instant placed = Instant.now().minus(Duration.ofDays(10));
        LocalDate promised = LocalDate.ofInstant(placed, ZoneOffset.UTC).plusDays(3);
        for (int i = 0; i < 20; i++) {
            String shipment = UUID.randomUUID().toString();
            List<ShipmentFact> facts = new ArrayList<>();
            facts.add(new ShipmentFact(shipment, "ord-" + i, org, placed, promised, null, null, null, "placed"));
            health.recordShipments(UUID.randomUUID(), facts);
            Instant delivered = i < 10 ? placed.plus(Duration.ofDays(2)) : i < 12 ? placed.plus(Duration.ofDays(6))
                    : null;
            if (delivered != null) {
                health.recordShipments(UUID.randomUUID(), List.of(new ShipmentFact(shipment, null, org, null, null,
                        delivered, null, null, "delivered")));
            }
            if (i == 12) {
                health.recordShipments(UUID.randomUUID(), List.of(new ShipmentFact(shipment, null, org, null, null,
                        null, placed.plusSeconds(3600), "SELLER", "cancelled")));
            }
            if (i == 13) {
                health.recordShipments(UUID.randomUUID(), List.of(new ShipmentFact(shipment, null, org, null, null,
                        null, placed.plusSeconds(3600), "CUSTOMER", "cancelled")));
            }
        }
    }

    private void stubAccount(String org) {
        UPSTREAM.stubFor(WireMock.get(WireMock.urlEqualTo("/v1/compliance/accounts"))
                .willReturn(WireMock.okJson("[{\"accountId\":\"acct_other\",\"orgId\":\"org_other\"},"
                        + "{\"accountId\":\"acct_" + org + "\",\"orgId\":\"" + org + "\"}]")));
    }

    @Test
    void metricsFromOrdersEventsArePushedToOffers() {
        String org = orgId();
        history(org);

        TestSupport.as(TestSupport.trustSafety("sara"));
        SellerHealthView view = health.health(org);
        SellerHealthReport r = view.current();
        assertThat(r.metrics().shipments()).isEqualTo(20);
        assertThat(r.metrics().delivered()).isEqualTo(12);
        assertThat(r.metrics().lateDeliveries()).isEqualTo(2);
        assertThat(r.metrics().sellerCancellations()).isEqualTo(1);
        assertThat(r.metrics().lateShipmentRate()).isCloseTo(2.0 / 12, within(1e-9));
        assertThat(r.status()).isEqualTo(SellerHealthStatus.AT_RISK);
        assertThat(view.standing().status()).isEqualTo(EnforcementStatus.ACTIVE);

        health.recompute(org);
        UPSTREAM.verify(WireMock.putRequestedFor(WireMock.urlEqualTo("/v1/sellers/" + org + "/performance"))
                .withHeader("Authorization", WireMock.equalTo("Bearer tok-trust-ops"))
                .withRequestBody(WireMock.matchingJsonPath("$.restricted", WireMock.equalTo("false")))
                .withRequestBody(WireMock.matchingJsonPath("$.rating", WireMock.equalTo("4.6")))
                .withRequestBody(WireMock.matchingJsonPath("$.onTimeRate", WireMock.containing("0.83"))));
        assertThat(health.health(org).lastPushedToOffers()).isTrue();
        assertThat(outbox(org)).contains("trustops.seller.health_computed");

        // The daily job covers every active seller.
        assertThat(health.computeAll()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void suspendHoldsPayoutsAndRestrictsTheBuyBoxThenReinstateReleasesBoth() {
        String org = orgId();
        stubAccount(org);
        TestSupport.as(TestSupport.trustSafety("sara"));

        SellerEnforcement suspended = enforcement.suspend(org, SuspensionReason.COUNTERFEIT, "Counterfeit wax prints");
        assertThat(suspended.status()).isEqualTo(EnforcementStatus.SUSPENDED);
        assertThat(suspended.payoutsHeld()).isTrue();
        assertThat(suspended.buyBoxRestricted()).isTrue();
        UPSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/compliance/accounts/acct_" + org
                        + "/payouts/pause"))
                .withRequestBody(WireMock.matchingJsonPath("$.note", WireMock.containing("Counterfeit wax prints"))));
        UPSTREAM.verify(WireMock.putRequestedFor(WireMock.urlEqualTo("/v1/sellers/" + org + "/performance"))
                .withRequestBody(WireMock.matchingJsonPath("$.restricted", WireMock.equalTo("true"))));
        assertThat(enforcement.suspended()).extracting(SellerEnforcement::orgId).contains(org);

        SellerEnforcement active = enforcement.reinstate(org, "Supplier invoices verified");
        assertThat(active.status()).isEqualTo(EnforcementStatus.ACTIVE);
        assertThat(active.payoutsHeld()).isFalse();
        assertThat(active.buyBoxRestricted()).isFalse();
        UPSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/v1/compliance/accounts/acct_" + org
                + "/payouts/resume")));
        UPSTREAM.verify(WireMock.putRequestedFor(WireMock.urlEqualTo("/v1/sellers/" + org + "/performance"))
                .withRequestBody(WireMock.matchingJsonPath("$.restricted", WireMock.equalTo("false"))));

        assertThat(jdbc.sql("select action from audit_entry where entity_type = 'ORG' and entity_id = :org "
                + "order by seq").param("org", org).query(String.class).list())
                .containsExactly("trustops.seller.suspended", "trustops.seller.reinstated");
        assertThat(outbox(org)).contains("trustops.seller.suspended", "trustops.seller.reinstated");
    }

    @Test
    void failedUpstreamStepStaysPendingAndIsRetried() {
        String org = orgId();
        stubAccount(org);
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/compliance/accounts/[^/]+/payouts/pause"))
                .willReturn(WireMock.serverError()));
        TestSupport.as(TestSupport.trustSafety("sara"));

        SellerEnforcement first = enforcement.suspend(org, SuspensionReason.FRAUD_RISK, "Chargeback ring");
        assertThat(first.status()).isEqualTo(EnforcementStatus.SUSPENDED);
        assertThat(first.payoutsHeld()).isFalse();
        assertThat(first.buyBoxRestricted()).isTrue();

        stubUpstreams();
        stubAccount(org);
        SellerEnforcement retried = enforcement.suspend(org, SuspensionReason.FRAUD_RISK, "Chargeback ring");
        assertThat(retried.payoutsHeld()).isTrue();
        assertThat(jdbc.sql("select count(*) from audit_entry where action = 'trustops.seller.suspended' "
                + "and entity_id = :org").param("org", org).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void enforcementNeedsTheNewPermission() {
        TestSupport.as(TestSupport.marketing());
        assertThatThrownBy(() -> enforcement.suspend(orgId(), SuspensionReason.OTHER, "x"))
                .isInstanceOf(ForbiddenActionException.class);
        problem(send("POST", "/v1/seller-enforcement/" + orgId() + "/suspend",
                Map.of("reason", "OTHER", "note", "x")), 403, "auth.missing-permission");
    }
}
