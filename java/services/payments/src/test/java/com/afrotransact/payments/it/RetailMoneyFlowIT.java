package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.application.EnforcementNotice;
import com.afrotransact.payments.application.SellerEnforcementService;
import com.afrotransact.payments.application.SettlementService;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * AfroTransact Retail is the merchant of record, not a marketplace seller: a mixed order (Retail lines + two sellers)
 * is captured, settled and refunded; Retail's net is 4400 revenue, it gets no transfer, no reversal and no payout.
 */
class RetailMoneyFlowIT extends PaymentsIntegrationTest {

    private SettlementService settlements;
    private SellerEnforcementService enforcement;

    @BeforeEach
    void wire(@Autowired SettlementService settlementService, @Autowired SellerEnforcementService enforcementService) {
        this.settlements = settlementService;
        this.enforcement = enforcementService;
    }

    @Test
    void mixedOrderCapturesSettlesAndRefundsWithoutTreatingRetailAsASeller() {
        String order = id("ORD");
        String orgA = id("org_ra").toLowerCase(Locale.ROOT);
        String orgB = id("org_rb").toLowerCase(Locale.ROOT);
        String acctA = "acct_" + orgA.replace("-", "").replace("_", "");
        String acctB = "acct_" + orgB.replace("-", "").replace("_", "");
        TestSupport.as(TestSupport.FINANCE);
        ok(send("PUT", "/v1/connected-accounts/" + orgA, Map.of("stripeAccountId", acctA), null));
        ok(send("PUT", "/v1/connected-accounts/" + orgB, Map.of("stripeAccountId", acctB), null));
        problem(send("PUT", "/v1/connected-accounts/" + RETAIL, Map.of("stripeAccountId", "acct_retail123"), null),
                422, "payments.retail-no-connected-account");

        // Retail: R1 FBA, R2 self-shipped; org_a self-shipped; org_b FBA. Charge 11 435, Stripe fee 362.
        JsonNode payment = createAuthorisedPayment(order, List.of(
                line("R1", RETAIL, "Groceries", "FBA", 2, 2400, 400, 198),
                line("R2", RETAIL, "Fashion", "SELF", 1, 1500, 300, 124),
                line("A1", orgA, "Groceries", "SELF", 2, 2000, 500, 165),
                line("B1", orgB, "Fashion", "FBA", 3, 3000, 600, 248)));
        String paymentId = payment.path("id").asString();
        STRIPE.chargeFees("ch_" + order, new long[] {11_435, 362});
        capture(paymentId, order, order + "-S1", List.of(), 11_435, 362, true);

        // Capture journal: Retail net to 4400, never 2000; tax to 2100; no FBA fee / commission from Retail.
        assertEveryJournalBalances();
        long retailNet = 3900 + 700 - 156;
        long netA = 2000 + 500 - 160 - 84;
        long netB = 3000 - 360 - 975 - 103;
        assertThat(balance(order, "4400", "")).isEqualTo(retailNet);
        assertThat(balance(order, "2000", RETAIL)).isZero();
        assertThat(balance(order, "2000", orgA)).isEqualTo(netA);
        assertThat(balance(order, "2000", orgB)).isEqualTo(netB);
        assertThat(balance(order, "2100", "US-TX-48201")).isEqualTo(198 + 124 + 165 + 248);
        assertThat(balance(order, "4000", "")).isEqualTo(160 + 360);
        assertThat(balance(order, "4100", "")).isEqualTo(975);
        assertThat(balance(order, "4200", "")).isEqualTo(600);
        assertThat(-balance(order, "5000", "")).isEqualTo(19);
        assertThat(-balance(order, "1000", "")).isEqualTo(11_435 - 362);
        assertThat(retailLinesOn(order, "4400")).isPositive();

        TestSupport.as(TestSupport.FINANCE);
        JsonNode split = ok(send("GET", "/v1/orders/" + order + "/split", null, null));
        for (JsonNode r : split.path("recipients")) {
            if (r.path("recipient").asString().equals(RETAIL)) {
                assertThat(r.path("kind").asString()).isEqualTo("RETAIL");
                assertThat(r.path("commissionMinor").asLong()).isZero();
                assertThat(r.path("fbaFeesMinor").asLong()).isZero();
                assertThat(r.path("netMinor").asLong()).isEqualTo(retailNet);
            }
        }

        // Settlement: transfers to org_a and org_b only.
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/settlements/" + order + "/delivered", Map.of(), null));
        STRIPE.transfer(order, acctA, "tr_a_" + order, netA);
        STRIPE.transfer(order, acctB, "tr_b_" + order, netB);
        TestSupport.CLOCK.advance(Duration.ofDays(8));
        settlements.releaseMatured(500);
        List<LoggedRequest> transfers = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/transfers")).withRequestBody(WireMock.containing("transfer_group=" + order)));
        assertThat(transfers).hasSize(2);
        assertThat(transfers).noneMatch(t -> t.getBodyAsString().contains(RETAIL));
        assertThat(transferredOrgs(order)).containsExactlyInAnyOrder(orgA, orgB);
        TestSupport.as(TestSupport.FINANCE);
        assertThat(ok(send("GET", "/v1/settlements/" + order, null, null)).path("status").asString())
                .isEqualTo("SETTLED");
        assertThat(balance(order, "2000", orgA)).isZero();
        assertThat(balance(order, "4400", "")).isEqualTo(retailNet);

        // Refund half of R1 (items + all its shipping + tax) and half of A1: Retail debits 4400, only org_a is reversed.
        STRIPE.refund("pi_" + order, "re_" + order, 1699 + 1000, "succeeded");
        STRIPE.reversal("tr_a_" + order, "trr_a_" + order, 1000 - 80);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode refund = ok(send("POST", "/v1/payments/" + paymentId + "/refunds", Map.of("lines", List.of(
                Map.of("lineId", "R1", "itemsMinor", 1200, "shippingMinor", 400, "taxMinor", 99),
                Map.of("lineId", "A1", "itemsMinor", 1000))), "refund-" + order), 201);
        assertThat(refund.path("transferReversals")).hasSize(1);
        assertThat(refund.path("transferReversals").get(0).path("orgId").asString()).isEqualTo(orgA);
        assertThat(refund.path("transferReversals").get(0).path("amountMinor").asLong()).isEqualTo(920);
        for (JsonNode l : refund.path("lines")) {
            if (l.path("lineId").asString().equals("R1")) {
                assertThat(l.path("commissionReversedMinor").asLong()).isZero();
            }
        }
        List<LoggedRequest> reversals = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathMatching(
                "/v1/transfers/tr_[ab]_" + order + "/reversals")));
        assertThat(reversals).hasSize(1);
        assertThat(reversals.getFirst().getUrl()).contains("tr_a_" + order);
        assertEveryJournalBalances();
        assertThat(balance(order, "4400", "")).isEqualTo(retailNet - 1200 - 400);
        assertThat(balance(order, "2000", RETAIL)).isZero();
        assertThat(balance(order, "2000", orgA)).isZero();
        assertThat(balance(order, "4000", "")).isEqualTo(160 + 360 - 80);
        assertThat(balance(order, "2100", "US-TX-48201")).isEqualTo(735 - 99);
        assertThat(balance(order, "2200", "")).isZero();
        assertThat(jdbc.sql("select count(*) from journal_line where order_id = :o and account_code = '2000' "
                + "and account_key = :r").param("o", order).param("r", RETAIL).query(Long.class).single()).isZero();

        // Payouts and ledger views: Retail is never a payable organisation.
        TestSupport.as(TestSupport.FINANCE);
        JsonNode retailBalance = ok(send("GET", "/v1/payouts/balance?orgId=" + RETAIL, null, null));
        assertThat(retailBalance.path("availableMinor").asLong()).isZero();
        assertThat(retailBalance.path("onHoldMinor").asLong()).isZero();
        assertThat(retailBalance.path("onHold")).isEmpty();
        assertThat(retailBalance.path("canRequestToday").asBoolean()).isFalse();
        problem(send("POST", "/v1/payouts/requests", Map.of("orgId", RETAIL, "all", true), "payout-" + order), 422,
                "payments.retail-no-payouts");
        JsonNode sellerBalance = ok(send("GET", "/v1/payouts/balance?orgId=" + orgB, null, null));
        assertThat(sellerBalance.path("availableMinor").asLong()).isEqualTo(netB);

        JsonNode orgBalances = ok(send("GET", "/v1/ledger/orgs/" + RETAIL + "/balances", null, null));
        assertThat(codes(orgBalances)).doesNotContain("2000", "2300").contains("4400");
        JsonNode all = ok(send("GET", "/v1/ledger/balances", null, null));
        assertThat(codes(all)).contains("4400");
        for (JsonNode b : all) {
            if (b.path("code").asString().equals("2000")) {
                assertThat(b.path("key").asString()).isNotEqualTo(RETAIL);
            }
            if (b.path("code").asString().equals("4400")) {
                assertThat(b.path("title").asString()).isEqualTo("AfroTransact Retail sales");
            }
        }

        // seller-service enforcement never holds Retail payouts.
        assertThat(enforcement.payoutsPaused(new EnforcementNotice(UUID.randomUUID(), RETAIL, null, "MANUAL",
                "staff-1", "test", TestSupport.CLOCK.instant()))).isFalse();
        assertThat(ok(send("GET", "/v1/payouts/balance?orgId=" + RETAIL, null, null)).path("payoutsPaused")
                .asBoolean()).isFalse();

        // Effective fees: zero commission, zero FBA, source RETAIL.
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode fees = ok(send("GET", "/v1/fee-schedules/effective?category=groceries&orgId=" + RETAIL, null,
                null));
        assertThat(fees.path("commissionBps").asInt()).isZero();
        assertThat(fees.path("fbaPerUnitMinor").asLong()).isZero();
        assertThat(fees.path("commissionSource").asString()).isEqualTo("RETAIL");
    }

    private long retailLinesOn(String order, String code) {
        return jdbc.sql("select count(*) from journal_line where order_id = :o and account_code = :c and org_id = :r")
                .param("o", order).param("c", code).param("r", RETAIL).query(Long.class).single();
    }

    private List<String> transferredOrgs(String order) {
        return jdbc.sql("select org_id from settlement_transfer where order_id = :o").param("o", order)
                .query(String.class).list();
    }

    private static List<String> codes(JsonNode balances) {
        List<String> out = new ArrayList<>();
        balances.forEach(b -> out.add(b.path("code").asString()));
        return out;
    }
}
