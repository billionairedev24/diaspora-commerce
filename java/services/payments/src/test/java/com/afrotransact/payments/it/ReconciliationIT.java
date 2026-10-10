package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Daily reconciliation of ledger 1000 Stripe clearing against Stripe balance transactions. */
class ReconciliationIT extends PaymentsIntegrationTest {

    private void capturedOn(Instant day, String order, long amount, long fee) {
        TestSupport.CLOCK.setNow(day.plusSeconds(12 * 3600));
        JsonNode payment = createAuthorisedPayment(order, List.of(line("L1", "org_rec", "Books", "SELF", 1, amount, 0,
                0)));
        STRIPE.chargeFees("ch_" + order, new long[] {amount, fee});
        capture(payment.path("id").asString(), order, "S1", List.of(), amount, fee, true);
    }

    private JsonNode reconcile(String date) {
        TestSupport.as(TestSupport.FINANCE);
        return ok(send("POST", "/v1/reconciliation/runs", Map.of("date", date), null), 201);
    }

    private long driftMetric() {
        String metrics = send("GET", "/actuator/prometheus", null, null).body();
        return metrics.lines().filter(l -> l.startsWith("payments_reconciliation_drift_minor"))
                .map(l -> l.substring(l.lastIndexOf(' ') + 1)).mapToLong(v -> (long) Double.parseDouble(v))
                .findFirst().orElseThrow();
    }

    @Test
    void matchingStripeActivityHasNoDrift() {
        Instant day = Instant.parse("2031-03-10T00:00:00Z");
        String order = id("ORD");
        capturedOn(day, order, 5000, 175);
        STRIPE.balanceTransactions(day.getEpochSecond(), StripeStubs.txn("txn_" + order, "charge", "ch_" + order,
                5000, 175, day.plusSeconds(43_200)) + "," + StripeStubs.txn("txn_po", "payout", "po_platform", -9000, 0,
                day.plusSeconds(50_000)));
        JsonNode run = reconcile("2031-03-10");
        assertThat(run.path("driftMinor").asLong()).isZero();
        assertThat(run.path("ledgerNetMinor").asLong()).isEqualTo(4825);
        assertThat(run.path("stripeNetMinor").asLong()).isEqualTo(4825);
        assertThat(driftMetric()).isZero();
    }

    @Test
    void driftIsDetectedAndExported() {
        Instant day = Instant.parse("2031-03-11T00:00:00Z");
        String order = id("ORD");
        capturedOn(day, order, 5000, 175);
        STRIPE.balanceTransactions(day.getEpochSecond(), StripeStubs.txn("txn_" + order, "charge", "ch_" + order,
                5000, 200, day.plusSeconds(43_200)) + "," + StripeStubs.txn("txn_x", "charge", "ch_outside", 1000, 0,
                day.plusSeconds(43_300)));
        JsonNode run = reconcile("2031-03-11");
        assertThat(run.path("driftMinor").asLong()).isEqualTo(25 + 1000);
        assertThat(run.path("drifts").size()).isEqualTo(2);
        assertThat(driftMetric()).isEqualTo(1025);
        assertThat(ok(send("GET", "/v1/reconciliation/runs?limit=5", null, null)).get(0).path("driftMinor")
                .asLong()).isEqualTo(1025);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        problem(send("POST", "/v1/reconciliation/runs", Map.of("date", "2031-03-11"), null), 403,
                "auth.missing-permission");
    }
}
