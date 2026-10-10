package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.application.EnforcementNotice;
import com.afrotransact.payments.application.SellerEnforcementService;
import com.afrotransact.payments.application.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** seller.payouts.paused / resumed (S205): held organisations cannot request or get payouts approved. */
class PayoutHoldIT extends PaymentsIntegrationTest {

    private static final String PAUSED = "payments.payouts_paused";

    private SettlementService settlements;
    private SellerEnforcementService enforcement;

    @BeforeEach
    void wire(@Autowired SettlementService settlementService, @Autowired SellerEnforcementService service) {
        this.settlements = settlementService;
        this.enforcement = service;
    }

    private static EnforcementNotice notice(String org, String account, String reason, Instant at) {
        return new EnforcementNotice(UUID.randomUUID(), org, account, reason, null, null, at);
    }

    /** Captures, delivers and releases one order so {@code org} has {@code net} available. */
    private long fundedOrg(String org, String account) {
        String order = id("ORD");
        TestSupport.as(TestSupport.FINANCE);
        ok(send("PUT", "/v1/connected-accounts/" + org, Map.of("stripeAccountId", account), null));
        JsonNode payment = createAuthorisedPayment(order, List.of(line("L1", org, "Groceries", "SELF", 1, 10_000, 0,
                825)));
        STRIPE.chargeFees("ch_" + order, new long[] {10_825, 320});
        capture(payment.path("id").asString(), order, order + "-S1", List.of(), 10_825, 320, true);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/settlements/" + order + "/delivered", Map.of(), null));
        long net = 10_000 - 800 - 320;
        STRIPE.transfer(order, account, "tr_" + org, net);
        TestSupport.CLOCK.advance(Duration.ofDays(7).plusMinutes(5));
        settlements.releaseMatured(500);
        return net;
    }

    @Test
    void pausedOrganisationsCannotRequestOrApprovePayouts() {
        String org = id("org_h").toLowerCase(Locale.ROOT);
        String account = "acct_" + org.replace("-", "").replace("_", "");
        long net = fundedOrg(org, account);
        Instant t0 = TestSupport.CLOCK.instant();

        EnforcementNotice pause = notice(org, account, "AUTO_PAST_DUE", t0);
        assertThat(enforcement.payoutsPaused(pause)).isTrue();
        assertThat(enforcement.payoutsPaused(pause)).as("redelivery is a no-op").isFalse();

        TestSupport.as(TestSupport.seller(org, "seller.owner", "payouts:request"));
        JsonNode p = problem(send("POST", "/v1/payouts/requests", Map.of("all", true), "payout-a-" + org), 422,
                PAUSED);
        assertThat(p.path("detail").asString()).contains("AUTO_PAST_DUE");
        assertThat(ok(send("GET", "/v1/payouts/balance", null, null)).path("availableMinor").asLong())
                .as("funds keep accruing").isEqualTo(net);

        assertThat(enforcement.payoutsResumed(notice(org, account, "CLEARED", t0.plusSeconds(60)))).isTrue();
        // An older pause delivered late does not re-pause.
        assertThat(enforcement.payoutsPaused(notice(org, account, "MANUAL", t0.plusSeconds(30)))).isFalse();

        JsonNode request = ok(send("POST", "/v1/payouts/requests", Map.of("all", true), "payout-b-" + org), 201);
        String requestId = request.path("id").asString();

        assertThat(enforcement.payoutsPaused(notice(org, account, "MANUAL", t0.plusSeconds(120)))).isTrue();
        TestSupport.as(TestSupport.FINANCE);
        problem(send("POST", "/v1/payouts/requests/" + requestId + "/approve", null, null), 422, PAUSED);

        assertThat(enforcement.payoutsResumed(notice(org, account, "CLEARED", t0.plusSeconds(180)))).isTrue();
        STRIPE.payout(account, "po_" + org, net);
        assertThat(ok(send("POST", "/v1/payouts/requests/" + requestId + "/approve", null, null)).path("status")
                .asString()).isEqualTo("IN_TRANSIT");
        assertThat(jdbc.sql("select count(*) from platform_inbox where consumer = 'payments.seller-compliance' "
                + "and event_id = :id").param("id", pause.eventId()).query(Long.class).single()).isEqualTo(1);
    }
}
