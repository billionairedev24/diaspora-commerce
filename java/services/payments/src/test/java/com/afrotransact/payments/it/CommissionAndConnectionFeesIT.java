package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.application.NegotiatedRateService;
import com.afrotransact.payments.application.OrgPlanService;
import com.afrotransact.payments.application.SubscriptionNotice;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Product commission and service connection fees on top of plan fees: plan rates from the seller's subscription
 * (event projection, seller API fallback), negotiated rates (dated, reasoned, audited, expired), the rate snapshotted
 * on every line, postings to 4000 / 4050, refunds reversing them and statements reporting them apart. Under the seed
 * schedule (V2 + V15): groceries 8 %, fashion 12 %, default 10 %; Seller Essentials 15 %, Seller Pro fashion 10 %;
 * connection fee 10 %, Provider Studio 8 %.
 */
class CommissionAndConnectionFeesIT extends PaymentsIntegrationTest {

    private OrgPlanService plans;
    private NegotiatedRateService negotiated;

    @BeforeEach
    void wire(@Autowired OrgPlanService orgPlanService, @Autowired NegotiatedRateService negotiatedRateService) {
        this.plans = orgPlanService;
        this.negotiated = negotiatedRateService;
    }

    private static String org(String prefix) {
        return id(prefix).toLowerCase(Locale.ROOT);
    }

    private void onPlan(String org, String plan) {
        assertThat(plans.subscriptionChanged(new SubscriptionNotice(UUID.randomUUID(), org, plan, "ACTIVE",
                Instant.now()))).isTrue();
    }

    private JsonNode split(String order) {
        TestSupport.as(TestSupport.FINANCE);
        return ok(send("GET", "/v1/orders/" + order + "/split", null, null));
    }

    private static Map<String, Object> serviceLine(String lineId, String org, String category, long amount) {
        Map<String, Object> l = new HashMap<>(line(lineId, org, category, "SELF", 1, amount, 0, 0));
        l.put("kind", "SERVICE");
        return l;
    }

    @Test
    void ordersPayThePlanRateUntilANegotiatedRateAndPlacedOrdersKeepTheirSnapshot() {
        String seller = org("org_cs");
        onPlan(seller, "seller_essentials");
        String first = id("ORD");
        JsonNode payment = createAuthorisedPayment(first, List.of(line("L1", seller, "Groceries", "SELF", 1, 10_000,
                0, 0)));
        JsonNode l1 = payment.path("lines").get(0);
        assertThat(l1.path("feeKind").asString()).isEqualTo("PRODUCT");
        assertThat(l1.path("rateBps").asInt()).isEqualTo(1500);
        assertThat(l1.path("rateSource").asString()).isEqualTo("PLAN");
        assertThat(l1.path("ratePlanCode").asString()).isEqualTo("seller_essentials");
        String paymentId = payment.path("id").asString();
        STRIPE.chargeFees("ch_" + first, new long[] {10_000, 320});
        capture(paymentId, first, "S1", List.of("L1"), 10_000, 320, true);
        assertThat(balance(first, "4000", "")).isEqualTo(1500);
        assertThat(balance(first, "4050", "")).isZero();

        // Staff negotiate 6 % on groceries: a reason is required, overlaps are refused, everything is audited.
        TestSupport.as(TestSupport.seller(seller, "seller.owner", "payouts:request"));
        problem(send("POST", "/v1/negotiated-rates", Map.of("orgId", seller, "type", "COMMISSION", "rateBps", 600,
                "reason", "x"), null), 403, "auth.missing-permission");
        TestSupport.as(TestSupport.FINANCE);
        assertThat(send("POST", "/v1/negotiated-rates", Map.of("orgId", seller, "type", "COMMISSION",
                "category", "Groceries", "rateBps", 600), null).statusCode()).isEqualTo(400);
        JsonNode deal = ok(send("POST", "/v1/negotiated-rates", Map.of("orgId", seller, "type", "COMMISSION",
                "category", "Groceries", "rateBps", 600, "reason", "Wholesale partner, 12 months",
                "negotiatedBy", "Ada (Partnerships)", "reference", "SF-1001",
                "endsAt", Instant.now().plus(Duration.ofDays(365)).toString()), null), 201);
        assertThat(deal.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(deal.path("category").asString()).isEqualTo("groceries");
        assertThat(deal.path("createdBy").asString()).isEqualTo("fin-1");
        assertThat(deal.path("negotiatedBy").asString()).isEqualTo("Ada (Partnerships)");
        assertThat(deal.path("audit").get(0).path("action").asString()).isEqualTo("CREATED");
        problem(send("POST", "/v1/negotiated-rates", Map.of("orgId", seller, "type", "COMMISSION",
                "category", "groceries", "rateBps", 500, "reason", "Second deal"), null), 409,
                "payments.negotiated-rate-overlap");
        problem(send("POST", "/v1/negotiated-rates", Map.of("orgId", RETAIL, "type", "COMMISSION", "rateBps", 500,
                "reason", "Never"), null), 422, "payments.retail-no-fees");

        String second = id("ORD");
        JsonNode p2 = createAuthorisedPayment(second, List.of(line("L1", seller, "Groceries", "SELF", 1, 10_000, 0, 0),
                line("L2", seller, "Fashion", "SELF", 1, 5_000, 0, 0)));
        assertThat(p2.path("lines").get(0).path("rateBps").asInt()).isEqualTo(600);
        assertThat(p2.path("lines").get(0).path("rateSource").asString()).isEqualTo("OVERRIDE");
        assertThat(p2.path("lines").get(0).path("negotiatedRateId").asString())
                .isEqualTo(deal.path("id").asString());
        assertThat(p2.path("lines").get(1).path("rateBps").asInt()).as("Essentials * for other categories")
                .isEqualTo(1500);
        // The first order keeps the rate it was placed with.
        JsonNode firstSplit = split(first);
        assertThat(firstSplit.path("lines").get(0).path("commissionBps").asInt()).isEqualTo(1500);
        assertThat(firstSplit.path("lines").get(0).path("rateSource").asString()).isEqualTo("PLAN");

        // Effective fees and the rate card say where each rate comes from.
        JsonNode effective = ok(send("GET", "/v1/fee-schedules/effective?category=groceries&orgId=" + seller, null,
                null));
        assertThat(effective.path("commissionBps").asInt()).isEqualTo(600);
        assertThat(effective.path("commissionSource").asString()).isEqualTo("OVERRIDE");
        assertThat(effective.path("negotiatedUntil").isNull()).isFalse();
        assertThat(effective.path("planCode").asString()).isEqualTo("seller_essentials");
        JsonNode card = ok(send("GET", "/v1/orgs/" + seller + "/rate-card", null, null));
        assertThat(card.path("kind").asString()).isEqualTo("PRODUCT");
        assertThat(card.path("otherRate").path("bps").asInt()).isEqualTo(1500);
        assertThat(card.path("otherRate").path("source").asString()).isEqualTo("PLAN");
        assertThat(card.path("negotiated")).hasSize(1);
        TestSupport.as(TestSupport.seller(seller, "seller.owner", "payouts:read"));
        assertThat(ok(send("GET", "/v1/orgs/" + seller + "/rate-card", null, null)).path("planCode").asString())
                .isEqualTo("seller_essentials");
        problem(send("GET", "/v1/orgs/org_someone_else/rate-card", null, null), 403, "auth.wrong-org");
        assertThat(ok(send("GET", "/v1/negotiated-rates", null, null))).hasSize(1);

        // Ending it (with a reason) brings the plan rate back for new orders.
        TestSupport.as(TestSupport.FINANCE);
        JsonNode ended = ok(send("POST", "/v1/negotiated-rates/" + deal.path("id").asString() + "/end",
                Map.of("reason", "Partner left the programme"), null), 200);
        assertThat(ended.path("status").asString()).isEqualTo("ENDED");
        assertThat(ended.path("endedBy").asString()).isEqualTo("fin-1");
        assertThat(ended.path("audit")).hasSize(2);
        problem(send("POST", "/v1/negotiated-rates/" + deal.path("id").asString() + "/end",
                Map.of("reason", "again"), null), 409, "payments.negotiated-rate-ended");
        JsonNode listed = ok(send("GET", "/v1/negotiated-rates?orgId=" + seller + "&status=ENDED", null, null));
        assertThat(listed).hasSize(1);
        assertThat(ok(send("GET", "/v1/fee-schedules/effective?category=groceries&orgId=" + seller, null, null))
                .path("commissionBps").asInt()).isEqualTo(1500);
        assertThat(split(second).path("lines").get(0).path("commissionBps").asInt()).isEqualTo(600);
        assertEveryJournalBalances();
    }

    @Test
    void anOrganisationWithoutAnEventIsAskedOnceFromTheSellerService() {
        String seller = org("org_fb");
        WIREMOCK.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/orgs/" + seller + "/billing"))
                .willReturn(WireMock.okJson("{\"orgId\":\"" + seller + "\",\"planCode\":\"seller_pro\","
                        + "\"status\":\"ACTIVE\"}")));
        String order = id("ORD");
        JsonNode p = createAuthorisedPayment(order, List.of(line("L1", seller, "Fashion", "SELF", 1, 10_000, 0, 0)));
        assertThat(p.path("lines").get(0).path("rateBps").asInt()).isEqualTo(1000);
        assertThat(p.path("lines").get(0).path("ratePlanCode").asString()).isEqualTo("seller_pro");
        assertThat(jdbc.sql("select source from org_plan where org_id = :o").param("o", seller).query(String.class)
                .single()).isEqualTo("API");
        createAuthorisedPayment(id("ORD"), List.of(line("L1", seller, "Fashion", "SELF", 1, 1_000, 0, 0)));
        WIREMOCK.verify(1, WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v1/orgs/" + seller + "/billing")));
        // A later event replaces the fallback; a cancelled subscription has no plan rates.
        assertThat(plans.subscriptionChanged(new SubscriptionNotice(UUID.randomUUID(), seller, "seller_pro",
                "CANCELED", Instant.now()))).isTrue();
        assertThat(plans.planOf(seller)).isEmpty();
        assertThat(plans.subscriptionChanged(new SubscriptionNotice(UUID.randomUUID(), seller, "seller_growth",
                "ACTIVE", Instant.now().minus(Duration.ofDays(1))))).as("older than the stored state").isFalse();
    }

    @Test
    void bookingsPayAConnectionFeeBookedApartFromCommissionAndRefundsGiveItBack() {
        String provider = org("org_cf");
        onPlan(provider, "provider_studio");
        String booking = id("BK");
        JsonNode payment = createAuthorisedPayment(booking, List.of(serviceLine("deposit", provider,
                "Massage", 2_000), serviceLine("balance", provider, "Massage", 8_000)));
        for (JsonNode l : payment.path("lines")) {
            assertThat(l.path("feeKind").asString()).isEqualTo("SERVICE");
            assertThat(l.path("rateBps").asInt()).isEqualTo(800);
            assertThat(l.path("rateSource").asString()).isEqualTo("PLAN");
        }
        String paymentId = payment.path("id").asString();
        STRIPE.chargeFees("ch_" + booking, new long[] {2_000, 88});
        capture(paymentId, booking, "deposit", List.of("deposit"), 2_000, 88, false);
        STRIPE.chargeFees("ch_" + booking, new long[] {2_000, 88}, new long[] {8_000, 262});
        capture(paymentId, booking, "balance", List.of("balance"), 8_000, 262, true);
        assertThat(balance(booking, "4050", "")).isEqualTo(160 + 640);
        assertThat(balance(booking, "4000", "")).isZero();
        assertThat(balance(booking, "2000", provider)).isEqualTo(10_000 - 800 - 88 - 262);
        JsonNode split = split(booking);
        JsonNode providerRow = null;
        for (JsonNode r : split.path("recipients")) {
            if (provider.equals(r.path("recipient").asString())) {
                providerRow = r;
            }
        }
        assertThat(providerRow).isNotNull();
        assertThat(providerRow.path("connectionFeesMinor").asLong()).isEqualTo(800);
        assertThat(providerRow.path("commissionMinor").asLong()).isZero();
        assertThat(split.path("lines").get(0).path("connectionFeeMinor").asLong()).isEqualTo(160);

        // The customer cancels late and gets half the deposit back: half its connection fee is returned
        // (reverseCommissionOnRefund) from 4050.
        STRIPE.refund("pi_" + booking, "re_" + booking, 1_000, "succeeded");
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode refund = ok(send("POST", "/v1/payments/" + paymentId + "/refunds", Map.of("reason",
                "Late cancellation", "lines", List.of(Map.of("lineId", "deposit", "itemsMinor", 1_000))),
                "refund-" + booking), 201);
        assertThat(refund.path("lines").get(0).path("commissionReversedMinor").asLong()).isEqualTo(80);
        assertThat(refund.path("lines").get(0).path("connectionFee").asBoolean()).isTrue();
        assertThat(balance(booking, "4050", "")).isEqualTo(800 - 80);
        assertThat(balance(booking, "4000", "")).isZero();
        assertEveryJournalBalances();

        // The statement reports the connection fee apart from commission.
        TestSupport.as(TestSupport.FINANCE);
        JsonNode statement = ok(send("GET", "/v1/payouts/statement?orgId=" + provider, null, null));
        long connection = 0;
        long commission = 0;
        for (JsonNode s : statement) {
            connection += s.path("connectionFeeMinor").asLong();
            commission += s.path("commissionMinor").asLong();
        }
        assertThat(connection).isEqualTo(800 - 80);
        assertThat(commission).isZero();

        // Take-home estimates ask the same rate (kind SERVICE, the service's category).
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode fees = ok(send("GET", "/v1/fee-schedules/effective?kind=SERVICE&category=massage&orgId=" + provider,
                null, null));
        assertThat(fees.path("commissionBps").asInt()).isEqualTo(800);
        assertThat(fees.path("commissionSource").asString()).isEqualTo("PLAN");
        assertThat(fees.path("kind").asString()).isEqualTo("SERVICE");
        assertThat(fees.path("fbaPerUnitMinor").asLong()).isZero();
    }

    @Test
    void legacyServiceLinesAndUnplannedProvidersPayTheDefaultConnectionFee() {
        String provider = org("org_lg");
        String booking = id("BK");
        JsonNode payment = createAuthorisedPayment(booking, List.of(line("deposit", provider, "services", "SELF", 1,
                5_000, 0, 0)));
        JsonNode l = payment.path("lines").get(0);
        assertThat(l.path("feeKind").asString()).isEqualTo("SERVICE");
        assertThat(l.path("rateBps").asInt()).isEqualTo(1000);
        assertThat(l.path("rateSource").asString()).isEqualTo("DEFAULT");
        STRIPE.chargeFees("ch_" + booking, new long[] {5_000, 175});
        capture(payment.path("id").asString(), booking, "deposit", List.of("deposit"), 5_000, 175, true);
        assertThat(balance(booking, "4050", "")).isEqualTo(500);
    }

    @Test
    void negotiatedConnectionFeesExpireAtTheirEndDate() {
        String provider = org("org_ex");
        TestSupport.as(TestSupport.FINANCE);
        JsonNode deal = ok(send("POST", "/v1/negotiated-rates", Map.of("orgId", provider, "type", "CONNECTION_FEE",
                "rateBps", 300, "reason", "Launch promotion", "endsAt",
                Instant.now().plus(Duration.ofHours(2)).toString()), null), 201);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        assertThat(ok(send("GET", "/v1/fee-schedules/effective?kind=SERVICE&category=nails&orgId=" + provider, null,
                null)).path("commissionBps").asInt()).isEqualTo(300);
        TestSupport.CLOCK.advance(Duration.ofHours(3));
        assertThat(negotiated.expireDue()).isGreaterThanOrEqualTo(1);
        TestSupport.as(TestSupport.FINANCE);
        JsonNode after = ok(send("GET", "/v1/negotiated-rates/" + deal.path("id").asString(), null, null));
        assertThat(after.path("status").asString()).isEqualTo("EXPIRED");
        assertThat(after.path("expiredAt").isNull()).isFalse();
        assertThat(after.path("audit").get(1).path("action").asString()).isEqualTo("EXPIRED");
        assertThat(ok(send("GET", "/v1/fee-schedules/effective?kind=SERVICE&category=nails&orgId=" + provider, null,
                null)).path("commissionBps").asInt()).isEqualTo(1000);
    }
}
