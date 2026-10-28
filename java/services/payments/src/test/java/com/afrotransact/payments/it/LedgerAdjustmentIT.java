package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Reconciliation differences and correcting journals against PostgreSQL and the Stripe stand-in: an open difference is
 * explained or corrected by an adjustment that one Finance person proposes and a different one approves, and only then
 * does the journal reach the ledger.
 */
class LedgerAdjustmentIT extends PaymentsIntegrationTest {

    private static final Set<String> FINANCE_PERMISSIONS = Set.of("payments:read", "ledger:adjust", "ledger:approve");

    private static final Actor PROPOSER = finance("fin-proposer");
    private static final Actor APPROVER = finance("fin-approver");

    private static Actor finance(String subject) {
        return new Actor(subject, PrincipalType.STAFF, Optional.empty(), Set.of("platform.finance"),
                FINANCE_PERMISSIONS);
    }

    /** Captures 50.00 with a 1.75 fee in the ledger while Stripe charged 2.00: the ledger holds 0.25 more. */
    private JsonNode driftOn(String date) {
        Instant day = Instant.parse(date + "T00:00:00Z");
        String order = id("ORD").replace("-", "");
        TestSupport.CLOCK.setNow(day.plusSeconds(12 * 3600));
        JsonNode payment = createAuthorisedPayment(order, List.of(line("L1", "org_adj", "Books", "SELF", 1, 5000, 0, 0)));
        STRIPE.chargeFees("ch_" + order, new long[] {5000, 175});
        capture(payment.path("id").asString(), order, "S1", List.of(), 5000, 175, true);
        STRIPE.balanceTransactions(day.getEpochSecond(), StripeStubs.txn("txn_" + order, "charge", "ch_" + order,
                5000, 200, day.plusSeconds(43_200)));
        runReconciliation(date);
        return item("ch_" + order);
    }

    private JsonNode runReconciliation(String date) {
        TestSupport.as(TestSupport.FINANCE);
        return ok(send("POST", "/v1/reconciliation/runs", Map.of("date", date), null), 201);
    }

    private JsonNode item(String stripeRef) {
        TestSupport.as(PROPOSER);
        for (JsonNode i : ok(send("GET", "/v1/reconciliation/items?limit=500", null, null))) {
            if (stripeRef.equals(i.path("stripeRef").asString()) && !"SUPERSEDED".equals(i.path("status").asString())) {
                return i;
            }
        }
        throw new AssertionError("no reconciliation item for " + stripeRef);
    }

    private static Map<String, Object> lines(String debit, String credit, long amount) {
        return Map.of("lines", List.of(Map.of("account", debit, "side", "DEBIT", "amountMinor", amount),
                Map.of("account", credit, "side", "CREDIT", "amountMinor", amount)));
    }

    private JsonNode propose(JsonNode item, String reason) {
        TestSupport.as(PROPOSER);
        Map<String, Object> body = new HashMap<>(lines("5000", "1000", 25));
        body.put("itemId", item.path("id").asString());
        body.put("reason", reason);
        return ok(send("POST", "/v1/ledger/adjustments", body, null), 201);
    }

    private long journalLines(String journalId) {
        return jdbc.sql("select count(*) from journal_line where journal_id = :j::uuid").param("j", journalId)
                .query(Long.class).single();
    }

    @Test
    void aDifferenceIsCorrectedOnlyAfterADifferentPersonApproves() {
        JsonNode item = driftOn("2031-05-01");
        assertThat(item.path("status").asString()).isEqualTo("OPEN");
        assertThat(item.path("differenceMinor").asLong()).isEqualTo(25);

        JsonNode proposed = propose(item, "Stripe charged a 2.00 fee, the ledger booked 1.75");
        assertThat(proposed.path("status").asString()).isEqualTo("PROPOSED");
        assertThat(proposed.path("journalId").isNull() || proposed.path("journalId").isMissingNode()).isTrue();
        assertThat(proposed.path("stripeRef").asString()).isEqualTo(item.path("stripeRef").asString());

        problem(send("POST", "/v1/ledger/adjustments/" + proposed.path("id").asString() + "/approve", Map.of(), null),
                403, "payments.adjustment-four-eyes");
        TestSupport.as(readOnly());
        problem(send("POST", "/v1/ledger/adjustments/" + proposed.path("id").asString() + "/approve", Map.of(), null),
                403, "auth.missing-permission");

        TestSupport.as(APPROVER);
        JsonNode posted = ok(send("POST", "/v1/ledger/adjustments/" + proposed.path("id").asString() + "/approve",
                Map.of("note", "Checked against the Stripe fee report"), null));
        assertThat(posted.path("status").asString()).isEqualTo("POSTED");
        assertThat(posted.path("decidedBy").asString()).isEqualTo("fin-approver");
        assertThat(journalLines(posted.path("journalId").asString())).isEqualTo(2);
        assertEveryJournalBalances();
        assertThat(item(item.path("stripeRef").asString()).path("status").asString()).isEqualTo("ADJUSTED");

        problem(send("POST", "/v1/ledger/adjustments/" + proposed.path("id").asString() + "/approve", Map.of(), null),
                422, "payments.adjustment-decided");

        // the next run of the same day sees the books and Stripe agree
        JsonNode rerun = runReconciliation("2031-05-01");
        assertThat(rerun.path("driftMinor").asLong()).as(rerun.toString()).isZero();
    }

    private static Actor readOnly() {
        return new Actor("fin-reader", PrincipalType.STAFF, Optional.empty(), Set.of("platform.finance"),
                Set.of("payments:read"));
    }

    @Test
    void aRejectedProposalPostsNothingAndTheProposerMayWithdrawTheirOwn() {
        JsonNode item = driftOn("2031-05-02");
        JsonNode first = propose(item, "First attempt at fixing the fee");
        problem(send("POST", "/v1/ledger/adjustments", Map.of("itemId", item.path("id").asString(), "reason",
                "A second one while the first waits", "lines", lines("5000", "1000", 25).get("lines")), null), 422,
                "payments.adjustment-pending");

        TestSupport.as(APPROVER);
        problem(send("POST", "/v1/ledger/adjustments/" + first.path("id").asString() + "/reject", Map.of(), null), 422,
                "payments.reason-required");
        JsonNode rejected = ok(send("POST", "/v1/ledger/adjustments/" + first.path("id").asString() + "/reject",
                Map.of("note", "The fee report shows 1.75, not 2.00"), null));
        assertThat(rejected.path("status").asString()).isEqualTo("REJECTED");
        assertThat(item(item.path("stripeRef").asString()).path("status").asString()).isEqualTo("OPEN");

        JsonNode second = propose(item, "Second attempt after checking again");
        TestSupport.as(PROPOSER);
        assertThat(ok(send("POST", "/v1/ledger/adjustments/" + second.path("id").asString() + "/reject",
                Map.of("note", "Withdrawn, found the real cause"), null)).path("status").asString())
                .isEqualTo("REJECTED");
        assertThat(ok(send("GET", "/v1/ledger/adjustments?status=REJECTED", null, null)).size())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void badAdjustmentsAreRefusedBeforeAnythingIsStored() {
        TestSupport.as(PROPOSER);
        problem(send("POST", "/v1/ledger/adjustments", Map.of("stripeRef", "ch_x", "reason", "Unbalanced on purpose",
                "lines", List.of(Map.of("account", "5000", "side", "DEBIT", "amountMinor", 25),
                        Map.of("account", "1000", "side", "CREDIT", "amountMinor", 20))), null), 422,
                "payments.journal-unbalanced");
        problem(send("POST", "/v1/ledger/adjustments", Map.of("stripeRef", "ch_x", "reason", "Unknown account here",
                "lines", lines("9999", "1000", 5).get("lines")), null), 422, "payments.adjustment-account");
        problem(send("POST", "/v1/ledger/adjustments", Map.of("reason", "Clearing line without a Stripe object",
                "lines", lines("5000", "1000", 5).get("lines")), null), 422, "payments.adjustment-stripe-ref");
        TestSupport.as(readOnly());
        problem(send("POST", "/v1/ledger/adjustments", Map.of("stripeRef", "ch_x", "reason", "No permission to propose",
                "lines", lines("5000", "1000", 5).get("lines")), null), 403, "auth.missing-permission");
        TestSupport.as(TestSupport.customer("cus_1"));
        problem(send("GET", "/v1/ledger/adjustments", null, null), 403, "auth.missing-permission");
    }

    @Test
    void aDifferenceCanBeExplainedOnceWithoutPostingAnything() {
        JsonNode item = driftOn("2031-05-03");
        long journalsBefore = jdbc.sql("select count(*) from journal_entry").query(Long.class).single();
        TestSupport.as(PROPOSER);
        String path = "/v1/reconciliation/items/" + item.path("id").asString() + "/explain";
        problem(send("POST", path, Map.of("note", "short"), null), 422, "payments.reason-required");
        JsonNode explained = ok(send("POST", path, Map.of("note", "Stripe fee rounding on a multi-capture charge"), null));
        assertThat(explained.path("status").asString()).isEqualTo("EXPLAINED");
        assertThat(explained.path("resolvedBy").asString()).isEqualTo("fin-proposer");
        problem(send("POST", path, Map.of("note", "Explaining the same item twice"), null), 422,
                "payments.reconciliation-not-open");
        assertThat(jdbc.sql("select count(*) from journal_entry").query(Long.class).single()).isEqualTo(journalsBefore);

        JsonNode detail = ok(send("GET", "/v1/reconciliation/items/" + item.path("id").asString(), null, null));
        assertThat(detail.path("item").path("status").asString()).isEqualTo("EXPLAINED");
        assertThat(detail.path("journals").size()).as("what the ledger booked against the Stripe object")
                .isGreaterThan(0);
        assertThat(detail.path("adjustments").size()).isZero();
    }

    @Test
    void theStripeLookupShowsTheLedgerEvenWhenStripeHasNoSuchObject() {
        JsonNode item = driftOn("2031-05-04");
        TestSupport.as(PROPOSER);
        JsonNode lookup = ok(send("GET", "/v1/stripe/lookup?ref=" + item.path("stripeRef").asString(), null, null));
        assertThat(lookup.path("ref").asString()).isEqualTo(item.path("stripeRef").asString());
        assertThat(lookup.path("journals").size()).isGreaterThan(0);
        assertThat(lookup.path("object").isNull() || lookup.path("object").isMissingNode()
                || lookup.path("problem").isNull()).isTrue();
        ok(send("GET", "/v1/stripe/lookup?ref=" + UUID.randomUUID(), null, null), 200, 422);
    }
}
