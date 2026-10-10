package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.afrotransact.payments.application.PaymentsEvents;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** S057 chargebacks: dispute.created debits the responsible sellers; closed posts the outcome. */
class DisputeIT extends PaymentsIntegrationTest {

    private String capturedTwoSellerOrder(String order) {
        JsonNode payment = createAuthorisedPayment(order, List.of(
                line("L1", "org_x1", "Fashion", "SELF", 1, 6000, 0, 0),
                line("L2", "org_x2", "Fashion", "SELF", 1, 4000, 0, 0)));
        STRIPE.chargeFees("ch_" + order, new long[] {10_000, 320});
        capture(payment.path("id").asString(), order, "S1", List.of(), 10_000, 320, true);
        return payment.path("id").asString();
    }

    private String dispute(String id, String order, long amount, String status) {
        return "{\"id\":\"" + id + "\",\"object\":\"dispute\",\"amount\":" + amount + ",\"currency\":\"usd\","
                + "\"charge\":\"ch_" + order + "\",\"payment_intent\":\"pi_" + order + "\",\"reason\":\"fraudulent\","
                + "\"status\":\"" + status + "\",\"balance_transactions\":[{\"id\":\"txn_d\",\"fee\":1500}]}";
    }

    @Test
    void lostChargebackIsBorneByTheResponsibleSellers() {
        String order = id("ORD");
        capturedTwoSellerOrder(order);
        long payableX1 = balance(order, "2000", "org_x1");
        String dp = "dp_" + order;

        webhook(event(id("evt"), "charge.dispute.created", dispute(dp, order, 5000, "needs_response")));
        assertThat(balance(order, "2000", "org_x1")).isEqualTo(payableX1 - 3000);
        assertThat(balance(order, "2300", "org_x1")).isEqualTo(3000);
        assertThat(balance(order, "2300", "org_x2")).isEqualTo(2000);
        assertThat(-balance(order, "1100", "")).isEqualTo(5000);
        assertThat(outbox(PaymentsEvents.DISPUTE_OPENED, order)).isEqualTo(1);

        webhook(event(id("evt"), "charge.dispute.created", dispute(dp, order, 5000, "needs_response")));
        assertThat(outbox(PaymentsEvents.DISPUTE_OPENED, order)).isEqualTo(1);

        webhook(event(id("evt"), "charge.dispute.closed", dispute(dp, order, 5000, "lost")));
        assertThat(balance(order, "2300", "org_x1")).isZero();
        assertThat(balance(order, "1100", "")).isZero();
        assertThat(balance(order, "2000", "org_x1")).isEqualTo(payableX1 - 3000);
        assertThat(jdbc.sql("select status from dispute where id = :id").param("id", dp).query(String.class)
                .single()).isEqualTo("LOST");
        assertThat(outbox(PaymentsEvents.DISPUTE_CLOSED, order)).isEqualTo(1);
        assertEveryJournalBalances();
    }

    @Test
    void wonChargebackGivesTheReserveBack() {
        String order = id("ORD");
        capturedTwoSellerOrder(order);
        long payableX2 = balance(order, "2000", "org_x2");
        String dp = "dp_" + order;
        webhook(event(id("evt"), "charge.dispute.created", dispute(dp, order, 10_000, "needs_response")));
        assertThat(balance(order, "2000", "org_x2")).isEqualTo(payableX2 - 4000);
        webhook(event(id("evt"), "charge.dispute.closed", dispute(dp, order, 10_000, "won")));
        assertThat(balance(order, "2000", "org_x2")).isEqualTo(payableX2);
        assertThat(balance(order, "2300", "org_x2")).isZero();
        assertThat(balance(order, "1100", "")).isZero();
        assertEveryJournalBalances();
    }
}
