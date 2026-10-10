package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.payments.application.SettlementService;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** S218 settlement per shipment; S229 refunds by order + line ids; S235 refunded amounts per line, shipping tax. */
class ShipmentSettlementAndOrderRefundIT extends PaymentsIntegrationTest {

    private SettlementService settlements;

    @BeforeEach
    void wire(@Autowired SettlementService settlementService) {
        this.settlements = settlementService;
    }

    @Test
    void refundByOrderSpreadsTheAmountAndLinesReportWhatWasRefunded() {
        String order = id("ORD");
        Map<String, Object> l1 = new HashMap<>(line("L1", "org_c", "Groceries", "SELF", 1, 10_000, 500, 866));
        l1.put("shippingTaxMinor", 41);
        JsonNode payment = createAuthorisedPayment(order, List.of(l1));
        String paymentId = payment.path("id").asString();
        assertThat(payment.path("lines").get(0).path("shippingTaxMinor").asLong()).isEqualTo(41);
        assertThat(payment.path("lines").get(0).path("itemsTaxMinor").asLong()).isEqualTo(825);
        STRIPE.chargeFees("ch_" + order, new long[] {11_366, 330});
        capture(paymentId, order, order + "-S1", List.of(), 11_366, 330, true);

        STRIPE.refund("pi_" + order, "re_" + order, 5683, "succeeded");
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        Map<String, Object> body = Map.of("lineIds", List.of("L1"), "amountMinor", 5683, "reason", "Claim C-1");
        JsonNode refund = ok(send("POST", "/v1/orders/" + order + "/refunds", body, "claim-" + order), 201);
        assertThat(refund.path("amountMinor").asLong()).isEqualTo(5683);
        assertThat(refund.path("lines").get(0).path("itemsMinor").asLong()).isEqualTo(5000);
        assertThat(refund.path("lines").get(0).path("shippingMinor").asLong()).isEqualTo(250);
        assertThat(refund.path("lines").get(0).path("taxMinor").asLong()).isEqualTo(433);
        JsonNode replay = ok(send("POST", "/v1/orders/" + order + "/refunds", body, "claim-" + order), 200);
        assertThat(replay.path("id").asString()).isEqualTo(refund.path("id").asString());

        JsonNode line = ok(send("GET", "/v1/payments?orderId=" + order, null, null)).path("lines").get(0);
        assertThat(line.path("refundedItemsMinor").asLong()).isEqualTo(5000);
        assertThat(line.path("refundedShippingMinor").asLong()).isEqualTo(250);
        assertThat(line.path("refundedTaxMinor").asLong()).isEqualTo(433);
        assertThat(line.path("refundedMinor").asLong()).isEqualTo(5683);
        assertThat(line.path("refundableMinor").asLong()).isEqualTo(11_366 - 5683);

        problem(send("POST", "/v1/orders/" + order + "/refunds", Map.of("lineIds", List.of("L1"),
                "amountMinor", 5684), "claim-too-much-" + order), 422, "payments.refund-exceeds-remaining");
        problem(send("POST", "/v1/orders/" + order + "/refunds", Map.of("lineIds", List.of("NOPE")),
                "claim-unknown-" + order), 422, "payments.unknown-line");
        problem(send("POST", "/v1/orders/ORD-NONE/refunds", Map.of("lineIds", List.of("L1")), "claim-none"), 404,
                "payments.payment-not-found");
        assertEveryJournalBalances();
    }

    @Test
    void eachShipmentSettlesAfterItsOwnHold() {
        String order = id("ORD");
        String org = id("org_s").toLowerCase(Locale.ROOT);
        String account = "acct_" + org.replace("-", "").replace("_", "");
        TestSupport.as(TestSupport.FINANCE);
        ok(send("PUT", "/v1/connected-accounts/" + org, Map.of("stripeAccountId", account), null));
        JsonNode payment = createAuthorisedPayment(order, List.of(
                line("L1", org, "Groceries", "SELF", 1, 10_000, 0, 825),
                line("L2", org, "Groceries", "SELF", 1, 4_000, 0, 330)));
        String paymentId = payment.path("id").asString();
        STRIPE.chargeFees("ch_" + order, new long[] {10_825, 320}, new long[] {4_330, 130});
        capture(paymentId, order, order + "-S1", List.of("L1"), 10_825, 320, false);
        long firstNet = balance(order, "2000", org);

        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode hold = ok(send("POST", "/v1/settlements/" + order + "/shipments/" + order + "-S1/delivered",
                Map.of(), null));
        assertThat(hold.path("status").asString()).isEqualTo("OPEN");
        assertThat(ok(send("POST", "/v1/settlements/" + order + "/shipments/" + order + "-S1/delivered", Map.of(),
                null)).path("releaseAt").asString()).isEqualTo(hold.path("releaseAt").asString());

        STRIPE.transfer(order, account, "tr_" + order + "_1", firstNet);
        TestSupport.CLOCK.advance(Duration.ofDays(7).plusMinutes(5));
        settlements.releaseMatured(500);
        List<LoggedRequest> transfers = transfersOf(order);
        assertThat(transfers).hasSize(1);
        assertThat(transfers.getFirst().getBodyAsString()).contains("amount=" + firstNet);
        assertThat(balance(order, "2000", org)).isZero();

        capture(paymentId, order, order + "-S2", List.of("L2"), 4_330, 130, true);
        long secondNet = balance(order, "2000", org);
        assertThat(secondNet).isPositive();
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/settlements/" + order + "/shipments/" + order + "-S2/delivered", Map.of(), null));
        ok(send("POST", "/v1/settlements/" + order + "/delivered", Map.of(), null));
        STRIPE.transfer(order, account, "tr_" + order + "_2", secondNet);
        TestSupport.CLOCK.advance(Duration.ofDays(7).plusMinutes(5));
        settlements.releaseMatured(500);
        transfers = transfersOf(order);
        assertThat(transfers).hasSize(2);
        assertThat(transfers.getLast().getBodyAsString()).contains("amount=" + secondNet);
        assertThat(balance(order, "2000", org)).isZero();
        settlements.releaseMatured(500);
        assertThat(transfersOf(order)).hasSize(2);

        TestSupport.as(TestSupport.FINANCE);
        JsonNode shipments = ok(send("GET", "/v1/settlements/" + order + "/shipments", null, null));
        assertThat(shipments).hasSize(2);
        shipments.forEach(s -> assertThat(s.path("status").asString()).isEqualTo("SETTLED"));
        assertEveryJournalBalances();
    }

    private static List<LoggedRequest> transfersOf(String order) {
        return WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/transfers"))
                .withRequestBody(WireMock.containing("transfer_group=" + order)));
    }
}
