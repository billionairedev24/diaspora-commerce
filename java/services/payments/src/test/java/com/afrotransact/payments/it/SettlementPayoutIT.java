package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.payments.application.PaymentsEvents;
import com.afrotransact.payments.application.SettlementService;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Settlement hold → Connect transfer → payout on request → payout.paid (S056), reversal after refund (S057). */
class SettlementPayoutIT extends PaymentsIntegrationTest {

    private SettlementService settlements;

    @BeforeEach
    void wire(@Autowired SettlementService settlementService) {
        this.settlements = settlementService;
    }

    /** Captures a one-line order for {@code org} and returns the payment id; seller net = 10 000 − 800 − 320. */
    private String capturedOrder(String order, String org, String account) {
        TestSupport.as(TestSupport.FINANCE);
        ok(send("PUT", "/v1/connected-accounts/" + org, Map.of("stripeAccountId", account), null));
        JsonNode payment = createAuthorisedPayment(order, List.of(line("L1", org, "Groceries", "SELF", 1, 10_000, 0,
                825)));
        STRIPE.chargeFees("ch_" + order, new long[] {10_825, 320});
        capture(payment.path("id").asString(), order, order + "-S1", List.of(), 10_825, 320, true);
        return payment.path("id").asString();
    }

    private int releaseMaturedOnlyFor(String order) {
        int before = transfers(order);
        settlements.releaseMatured(500);
        return transfers(order) - before;
    }

    private int transfers(String order) {
        return jdbc.sql("select count(*) from settlement_transfer where order_id = :o").param("o", order)
                .query(Integer.class).single();
    }

    /**
     * ADR 0005 §5: a community pre-order package settles like any other line. The seller's net is held for the
     * settlement period after delivery and then transferred; the window's delivery fee (4250) stays with AfroTransact.
     */
    @Test
    void preorderSellerNetIsHeldAfterDeliveryThenTransferredAndTheDeliveryFeeStaysWithAfroTransact() {
        String order = id("PRE");
        String org = id("org_p").toLowerCase(Locale.ROOT);
        String account = "acct_" + org.replace("-", "").replace("_", "");
        TestSupport.as(TestSupport.FINANCE);
        ok(send("PUT", "/v1/connected-accounts/" + org, Map.of("stripeAccountId", account), null));
        JsonNode payment = createAuthorisedPayment(order, List.of(line("L1", org, "Groceries", "PREORDER", 1, 10_000,
                799, 825)));
        STRIPE.chargeFees("ch_" + order, new long[] {11_624, 320});
        capture(payment.path("id").asString(), order, order + "-S1", List.of(), 11_624, 320, true);
        long net = balance(order, "2000", org);
        assertThat(net).as("the seller's payable excludes the delivery fee").isPositive().isLessThan(10_000);
        assertThat(balance(order, "4250", "")).isEqualTo(799);

        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/settlements/" + order + "/delivered", Map.of(), null));
        STRIPE.transfer(order, account, "tr_" + org, net);
        TestSupport.CLOCK.advance(Duration.ofDays(7).minusMinutes(5));
        assertThat(releaseMaturedOnlyFor(order)).isZero();
        TestSupport.CLOCK.advance(Duration.ofMinutes(10));
        assertThat(releaseMaturedOnlyFor(order)).isEqualTo(1);
        LoggedRequest transfer = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/transfers"))
                .withRequestBody(WireMock.containing("transfer_group=" + order))).getFirst();
        assertThat(transfer.getBodyAsString()).contains("amount=" + net, "destination=" + account);
        assertThat(balance(order, "2000", org)).isZero();
        assertThat(balance(order, "4250", "")).isEqualTo(799);
        assertEveryJournalBalances();
    }

    @Test
    void transfersOnlyAfterTheHoldThenPayoutOnRequest() {
        String order = id("ORD");
        String org = id("org_s").toLowerCase(Locale.ROOT);
        String account = "acct_" + org.replace("-", "").replace("_", "");
        capturedOrder(order, org, account);
        long net = 10_000 - 800 - 320;

        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode hold = ok(send("POST", "/v1/settlements/" + order + "/delivered", Map.of(), null));
        assertThat(hold.path("status").asString()).isEqualTo("OPEN");

        TestSupport.as(TestSupport.seller(org, "seller.owner", "payouts:request"));
        JsonNode balance = ok(send("GET", "/v1/payouts/balance", null, null));
        assertThat(balance.path("availableMinor").asLong()).isZero();
        assertThat(balance.path("onHoldMinor").asLong()).isEqualTo(net);
        assertThat(balance.path("onHold").get(0).path("orderId").asString()).isEqualTo(order);
        assertThat(balance.path("onHold").get(0).path("releaseAt").asString())
                .isEqualTo(hold.path("releaseAt").asString());

        STRIPE.transfer(order, account, "tr_" + org, net);
        TestSupport.CLOCK.advance(Duration.ofDays(7).minusMinutes(5));
        assertThat(releaseMaturedOnlyFor(order)).isZero();
        WIREMOCK.verify(0, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/transfers"))
                .withRequestBody(WireMock.containing("transfer_group=" + order)));

        TestSupport.CLOCK.advance(Duration.ofMinutes(10));
        assertThat(releaseMaturedOnlyFor(order)).isEqualTo(1);
        LoggedRequest transfer = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/transfers"))
                .withRequestBody(WireMock.containing("transfer_group=" + order))).getFirst();
        assertThat(transfer.getBodyAsString()).contains("amount=" + net, "destination=" + account, "currency=usd");
        assertThat(transfer.getHeader("Idempotency-Key")).isEqualTo(order + ":transfer:" + org + ":1");
        assertThat(balance(order, "2000", org)).isZero();
        assertThat(outbox(PaymentsEvents.TRANSFER_CREATED, order)).isEqualTo(1);
        assertThat(releaseMaturedOnlyFor(order)).isZero();
        assertEveryJournalBalances();

        TestSupport.as(TestSupport.seller(org, "seller.owner", "payouts:request"));
        balance = ok(send("GET", "/v1/payouts/balance", null, null));
        assertThat(balance.path("availableMinor").asLong()).isEqualTo(net);
        assertThat(balance.path("onHoldMinor").asLong()).isZero();
        assertThat(balance.path("minimumPayoutMinor").asLong()).isEqualTo(2500);

        problem(send("POST", "/v1/payouts/requests", Map.of("amountMinor", 2499), null), 422,
                "payments.payout-below-minimum");
        problem(send("POST", "/v1/payouts/requests", Map.of("amountMinor", net + 1), null), 422,
                "payments.payout-exceeds-available");
        TestSupport.as(TestSupport.seller(org, "seller.catalog_manager", "payouts:request"));
        problem(send("POST", "/v1/payouts/requests", Map.of("all", true), null), 403, "payments.payout-role");
        TestSupport.as(TestSupport.seller(org, "seller.staff"));
        problem(send("POST", "/v1/payouts/requests", Map.of("all", true), null), 403, "auth.missing-permission");

        TestSupport.as(TestSupport.seller(org, "seller.owner", "payouts:request"));
        JsonNode request = ok(send("POST", "/v1/payouts/requests", Map.of("all", true), "payout-" + org), 201);
        assertThat(request.path("status").asString()).isEqualTo("REQUESTED");
        assertThat(request.path("amountMinor").asLong()).isEqualTo(net);
        assertThat(outbox(PaymentsEvents.PAYOUT_REQUESTED, org)).isEqualTo(1);
        problem(send("POST", "/v1/payouts/requests", Map.of("amountMinor", 2500), null), 422,
                "payments.payout-daily-limit");
        balance = ok(send("GET", "/v1/payouts/balance", null, null));
        assertThat(balance.path("availableMinor").asLong()).isZero();
        assertThat(balance.path("requestedOrInTransitMinor").asLong()).isEqualTo(net);
        assertThat(balance.path("canRequestToday").asBoolean()).isFalse();
        String requestId = request.path("id").asString();
        problem(send("POST", "/v1/payouts/requests/" + requestId + "/approve", null, null), 403,
                "auth.missing-permission");
        // The seller's own history: members list their organisation's requests (not the Finance queue).
        JsonNode mine = ok(send("GET", "/v1/payouts/requests?orgId=" + org, null, null));
        assertThat(mine.toString()).contains(requestId);
        problem(send("GET", "/v1/payouts/requests?orgId=org_someone_else", null, null), 403, "auth.wrong-org");

        TestSupport.as(TestSupport.FINANCE);
        JsonNode queue = ok(send("GET", "/v1/payouts/requests?status=REQUESTED&orgId=" + org, null, null));
        assertThat(queue.size()).isEqualTo(1);
        assertThat(ok(send("POST", "/v1/payouts/requests/" + requestId + "/hold", Map.of("reason", "Check bank"),
                null)).path("status").asString()).isEqualTo("HELD");
        assertThat(ok(send("POST", "/v1/payouts/requests/" + requestId + "/release", null, null)).path("status")
                .asString()).isEqualTo("REQUESTED");
        STRIPE.payout(account, "po_" + org, net);
        JsonNode approved = ok(send("POST", "/v1/payouts/requests/" + requestId + "/approve", null, null));
        assertThat(approved.path("status").asString()).isEqualTo("IN_TRANSIT");
        assertThat(approved.path("stripePayoutId").asString()).isEqualTo("po_" + org);
        LoggedRequest payout = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payouts"))
                .withHeader("Stripe-Account", WireMock.equalTo(account))).getFirst();
        assertThat(payout.getBodyAsString()).contains("amount=" + net);
        assertThat(payout.getHeader("Idempotency-Key")).isEqualTo("payout:" + requestId);
        assertThat(outbox(PaymentsEvents.PAYOUT_APPROVED, org)).isEqualTo(1);

        webhook(event(id("evt"), "payout.paid", "{\"id\":\"po_" + org + "\",\"object\":\"payout\",\"status\":"
                + "\"paid\",\"amount\":" + net + "}"));
        assertThat(ok(send("GET", "/v1/payouts/requests/" + requestId, null, null)).path("status").asString())
                .isEqualTo("PAID");
        assertThat(outbox(PaymentsEvents.PAYOUT_PAID, org)).isEqualTo(1);
        TestSupport.as(TestSupport.seller(org, "seller.staff"));
        assertThat(ok(send("GET", "/v1/payouts/balance", null, null)).path("paidMinor").asLong()).isEqualTo(net);
    }

    @Test
    void refundAfterTheTransferReversesTheSellersShare() {
        String order = id("ORD");
        String org = id("org_r").toLowerCase(Locale.ROOT);
        String account = "acct_" + org.replace("-", "").replace("_", "");
        String paymentId = capturedOrder(order, org, account);
        long net = 10_000 - 800 - 320;
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/settlements/" + order + "/delivered", Map.of("kind", "SERVICE"), null));
        STRIPE.transfer(order, account, "tr_" + org, net);
        TestSupport.CLOCK.advance(Duration.ofDays(8));
        assertThat(releaseMaturedOnlyFor(order)).isEqualTo(1);

        STRIPE.refund("pi_" + order, "re_" + order, 2000, "pending");
        STRIPE.reversal("tr_" + org, "trr_" + org, 1840);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode refund = ok(send("POST", "/v1/payments/" + paymentId + "/refunds", Map.of("lines", List.of(
                Map.of("lineId", "L1", "itemsMinor", 2000))), "refund-" + order), 201);
        assertThat(refund.path("status").asString()).isEqualTo("PENDING");
        assertThat(refund.path("transferReversals").get(0).path("amountMinor").asLong()).isEqualTo(2000 - 160);
        LoggedRequest reversal = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/transfers/tr_" + org + "/reversals"))).getFirst();
        assertThat(reversal.getBodyAsString()).contains("amount=1840");
        assertThat(balance(order, "2000", org)).isZero();
        assertThat(balance(order, "2200", "")).isEqualTo(2000);
        assertThat(jdbc.sql("select reversed_minor from settlement_transfer where stripe_transfer_id = :t")
                .param("t", "tr_" + org).query(Long.class).single()).isEqualTo(1840);

        // charge.refunded confirms Stripe paid the refund: Dr 2200 / Cr 1000.
        webhook(event(id("evt"), "charge.refunded", "{\"id\":\"ch_" + order + "\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_" + order + "\",\"amount_refunded\":2000}"));
        assertThat(balance(order, "2200", "")).isZero();
        // A reversal we made ourselves arriving as transfer.reversed is not booked twice.
        webhook(event(id("evt"), "transfer.reversed", "{\"id\":\"tr_" + org + "\",\"object\":\"transfer\","
                + "\"amount_reversed\":1840,\"reversals\":{\"data\":[{\"id\":\"trr_" + org + "\",\"amount\":1840}]}}"));
        assertThat(balance(order, "2000", org)).isZero();
        // A reversal made in the Stripe Dashboard is booked back to the seller payable.
        webhook(event(id("evt"), "transfer.reversed", "{\"id\":\"tr_" + org + "\",\"object\":\"transfer\","
                + "\"amount_reversed\":2840,\"reversals\":{\"data\":[{\"id\":\"trr_" + org + "\",\"amount\":1840},"
                + "{\"id\":\"trr_dash_" + org + "\",\"amount\":1000}]}}"));
        assertThat(balance(order, "2000", org)).isEqualTo(1000);
        assertEveryJournalBalances();
        TestSupport.as(TestSupport.FINANCE);
        assertThat(ok(send("GET", "/v1/settlements/" + order, null, null)).path("kind").asString())
                .isEqualTo("SERVICE");
    }
}
