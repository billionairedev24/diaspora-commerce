package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.payments.application.PaymentsEvents;
import com.afrotransact.payments.application.SettlementService;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * A refund after the seller was paid out: Stripe refuses to reverse the transfer (balance_insufficient) and the
 * customer's refund must still complete. What the seller owes the platform is a transfer recovery: a negative seller
 * payable, netted against the seller's next transfers, reserved in the payout balance and resolved by Finance with
 * four eyes. Transient Stripe failures still fail the refund, which retries under the same key.
 */
class TransferRecoveryIT extends PaymentsIntegrationTest {

    private static final Set<String> FINANCE_PERMISSIONS = Set.of("payments:read", "ledger:adjust", "ledger:approve");
    private static final Actor PROPOSER = finance("fin-proposer");
    private static final Actor APPROVER = finance("fin-approver");
    private static final String WRITE_OFF_REASON = "The seller closed the shop and cannot be reached";

    private SettlementService settlements;
    private MeterRegistry meters;

    private static Actor finance(String subject) {
        return new Actor(subject, PrincipalType.STAFF, Optional.empty(), Set.of("platform.finance"),
                FINANCE_PERMISSIONS);
    }

    @BeforeEach
    void wire(@Autowired SettlementService settlementService, @Autowired MeterRegistry registry) {
        this.settlements = settlementService;
        this.meters = registry;
    }

    /** An organisation, its order and its connected account; the order is captured, delivered and transferred. */
    private record Shop(String org, String account, String order, String paymentId, long net) {
    }

    private Shop newShop() {
        String org = id("org_r").toLowerCase(Locale.ROOT);
        return transferredOrder(id("ORD"), org, "acct_" + org.replace("-", "").replace("_", ""), 10_000, 825, 320);
    }

    /** Captures a one-line order of {@code org}, delivers it and lets the settlement job transfer the net. */
    private Shop transferredOrder(String order, String org, String account, long items, long tax, long fee) {
        TestSupport.as(TestSupport.FINANCE);
        ok(send("PUT", "/v1/connected-accounts/" + org, Map.of("stripeAccountId", account), null));
        JsonNode payment = createAuthorisedPayment(order, List.of(line("L1", org, "Groceries", "SELF", 1, items, 0,
                tax)));
        STRIPE.chargeFees("ch_" + order, new long[] {items + tax, fee});
        capture(payment.path("id").asString(), order, order + "-S1", List.of(), items + tax, fee, true);
        long net = balance(order, "2000", org);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/settlements/" + order + "/delivered", Map.of("kind", "SERVICE"), null));
        STRIPE.transfer(order, account, "tr_" + order, net);
        return new Shop(org, account, order, payment.path("id").asString(), net);
    }

    /** Moves the clock past the hold and runs the settlement job; returns the transfers made for the order. */
    private int release(Shop shop) {
        TestSupport.CLOCK.advance(Duration.ofDays(8));
        settlements.releaseMatured(500);
        return jdbc.sql("select count(*) from settlement_transfer where order_id = :o").param("o", shop.order())
                .query(Integer.class).single();
    }

    private JsonNode refund(Shop shop, long items, String key) {
        STRIPE.refund("pi_" + shop.order(), "re_" + key, items, "pending");
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        return ok(send("POST", "/v1/payments/" + shop.paymentId() + "/refunds", Map.of("lines", List.of(
                Map.of("lineId", "L1", "itemsMinor", items))), key), 201);
    }

    /** The shop's order is transferred and a 20.00 refund is refused by Stripe: 18.40 is owed by the seller. */
    private JsonNode refusedRefund(Shop shop, String key) {
        assertThat(release(shop)).isEqualTo(1);
        STRIPE.reversalRefused("tr_" + shop.order(), "balance_insufficient");
        return refund(shop, 2000, key);
    }

    /** Σ of every 2000 line of the organisation (credit positive): what AfroTransact owes it, negative when it owes. */
    private long orgPayable(String org) {
        return jdbc.sql("""
                select coalesce(sum(case side when 'C' then amount_minor else -amount_minor end), 0)
                from journal_line where account_code = '2000' and account_key = :k
                """).param("k", org).query(Long.class).single();
    }

    /** Debit-positive balance of an account, for the lines attributed to the organisation. */
    private long debits(String code, String org) {
        return jdbc.sql("""
                select coalesce(sum(case side when 'D' then amount_minor else -amount_minor end), 0)
                from journal_line where account_code = :c and org_id = :o
                """).param("c", code).param("o", org).query(Long.class).single();
    }

    private long journals(String type, String org) {
        return jdbc.sql("""
                select count(distinct e.id) from journal_entry e join journal_line l on l.journal_id = e.id
                where e.type = :t and l.org_id = :o
                """).param("t", type).param("o", org).query(Long.class).single();
    }

    private JsonNode balanceOf(String org) {
        TestSupport.as(TestSupport.seller(org, "seller.owner", "payouts:request"));
        return ok(send("GET", "/v1/payouts/balance", null, null));
    }

    private JsonNode recovery(String id) {
        TestSupport.as(PROPOSER);
        return ok(send("GET", "/v1/transfer-recoveries/" + id, null, null));
    }

    private JsonNode propose(String id, String resolution, String reason, String reference) {
        TestSupport.as(PROPOSER);
        Map<String, Object> body = new HashMap<>(Map.of("resolution", resolution, "reason", reason));
        if (reference != null) {
            body.put("reference", reference);
        }
        return ok(send("POST", "/v1/transfer-recoveries/" + id + "/propose", body, null));
    }

    // ------------------------------------------------------------------ the refund completes

    @Test
    void aRefusedReversalCompletesTheRefundAndOpensARecoveryTheSellerOwes() {
        Shop shop = newShop();
        double before = meters.counter("payments.transfer.recoveries", "outcome", "opened").count();
        JsonNode refund = refusedRefund(shop, "refund-" + shop.order());

        assertThat(refund.path("status").asString()).as("the customer's refund went through").isEqualTo("PENDING");
        assertThat(refund.path("stripeRefundId").asString()).isEqualTo("re_refund-" + shop.order());
        assertThat(refund.path("transferReversals")).isEmpty();
        assertThat(refund.path("transferRecoveries")).hasSize(1);
        JsonNode owed = refund.path("transferRecoveries").get(0);
        assertThat(owed.path("orgId").asString()).isEqualTo(shop.org());
        assertThat(owed.path("orderId").asString()).isEqualTo(shop.order());
        assertThat(owed.path("stripeTransferId").asString()).isEqualTo("tr_" + shop.order());
        assertThat(owed.path("amountMinor").asLong()).isEqualTo(2000 - 160);
        assertThat(owed.path("outstandingMinor").asLong()).isEqualTo(2000 - 160);
        assertThat(owed.path("status").asString()).isEqualTo("OPEN");
        assertThat(owed.path("stripeCode").asString()).isEqualTo("balance_insufficient");

        // Nothing came back from Stripe: the transfer is not reversed, the seller's payable is what it owes.
        assertThat(jdbc.sql("select reversed_minor from settlement_transfer where stripe_transfer_id = :t")
                .param("t", "tr_" + shop.order()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from transfer_reversal r join settlement_transfer t on t.id = "
                + "r.transfer_id where t.order_id = :o").param("o", shop.order()).query(Long.class).single()).isZero();
        assertThat(balance(shop.order(), "2000", shop.org())).as("the order's payable is whole again").isZero();
        assertThat(orgPayable(shop.org())).as("the seller owes the platform").isEqualTo(-(2000 - 160));
        assertThat(journals("TRANSFER_RECOVERY_OPENED", shop.org())).isEqualTo(1);
        assertThat(balance(shop.order(), "2200", "")).as("the refund is owed to the customer until Stripe pays it")
                .isEqualTo(2000);
        assertEveryJournalBalances();

        LoggedRequest attempt = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/transfers/tr_" + shop.order() + "/reversals"))).getFirst();
        assertThat(attempt.getHeader("Idempotency-Key")).startsWith(shop.order() + ":reversal:");
        assertThat(attempt.getBodyAsString()).contains("amount=1840");
        assertThat(outbox(PaymentsEvents.REFUNDED, shop.order())).isEqualTo(1);
        assertThat(meters.counter("payments.transfer.recoveries", "outcome", "opened").count() - before)
                .isEqualTo(1.0);
        assertThat(meters.counter("payments.transfer.reversals.refused", "code", "balance_insufficient").count())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void replayingTheRefundDoesNotOpenASecondRecoveryOrCallStripeAgain() {
        Shop shop = newShop();
        String key = "refund-" + shop.order();
        JsonNode first = refusedRefund(shop, key);
        long journalsBefore = journals("TRANSFER_RECOVERY_OPENED", shop.org());

        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode replay = ok(send("POST", "/v1/payments/" + shop.paymentId() + "/refunds", Map.of("lines", List.of(
                Map.of("lineId", "L1", "itemsMinor", 2000))), key), 200, 201);

        assertThat(replay.path("id").asString()).isEqualTo(first.path("id").asString());
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        assertThat(replay.path("transferRecoveries")).hasSize(1);
        assertThat(replay.path("transferRecoveries").get(0).path("id").asString())
                .isEqualTo(first.path("transferRecoveries").get(0).path("id").asString());
        assertThat(jdbc.sql("select count(*) from transfer_recovery where order_id = :o").param("o", shop.order())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(journals("TRANSFER_RECOVERY_OPENED", shop.org())).isEqualTo(journalsBefore);
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/transfers/tr_" + shop.order() + "/reversals")))).hasSize(1);
        assertThat(orgPayable(shop.org())).isEqualTo(-(2000 - 160));
        assertEveryJournalBalances();
    }

    @Test
    void aTransientStripeFailureStillFailsTheRefundAndTheRetryUnderTheSameKeySucceeds() {
        Shop shop = newShop();
        assertThat(release(shop)).isEqualTo(1);
        String key = "refund-" + shop.order();
        STRIPE.reversalDown("tr_" + shop.order());
        STRIPE.refund("pi_" + shop.order(), "re_" + key, 2000, "pending");
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        problem(send("POST", "/v1/payments/" + shop.paymentId() + "/refunds", Map.of("lines", List.of(
                Map.of("lineId", "L1", "itemsMinor", 2000))), key), 502, "payments.stripe-unavailable");

        assertThat(jdbc.sql("select count(*) from transfer_recovery where order_id = :o").param("o", shop.order())
                .query(Long.class).single()).as("a 5xx is not a refusal").isZero();
        assertThat(jdbc.sql("select count(*) from payment_refund where order_id = :o").param("o", shop.order())
                .query(Long.class).single()).as("the refund rolled back and can be retried").isZero();
        assertThat(orgPayable(shop.org())).isZero();

        STRIPE.reversal("tr_" + shop.order(), "trr_" + shop.order(), 1840);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode retried = ok(send("POST", "/v1/payments/" + shop.paymentId() + "/refunds", Map.of("lines", List.of(
                Map.of("lineId", "L1", "itemsMinor", 2000))), key), 201);
        assertThat(retried.path("transferReversals")).hasSize(1);
        assertThat(retried.path("transferReversals").get(0).path("amountMinor").asLong()).isEqualTo(1840);
        assertThat(retried.path("transferRecoveries")).isEmpty();
        assertThat(orgPayable(shop.org())).isZero();
        assertEveryJournalBalances();
    }

    // ------------------------------------------------------------------ netting against earnings

    @Test
    void aSuccessfulReversalOpensNoRecovery() {
        Shop shop = newShop();
        assertThat(release(shop)).isEqualTo(1);
        STRIPE.reversal("tr_" + shop.order(), "trr_" + shop.order(), 1840);
        JsonNode refund = refund(shop, 2000, "refund-" + shop.order());

        assertThat(refund.path("transferReversals")).hasSize(1);
        assertThat(refund.path("transferRecoveries")).isEmpty();
        assertThat(jdbc.sql("select count(*) from transfer_recovery where order_id = :o").param("o", shop.order())
                .query(Long.class).single()).isZero();
        assertThat(orgPayable(shop.org())).isZero();
        assertEveryJournalBalances();
    }

    @Test
    void thePayoutBalanceNetsWhatTheSellerOwesBeforeAnyPayout() {
        Shop shop = newShop();
        assertThat(balanceOf(shop.org()).path("availableMinor").asLong()).isZero();
        refusedRefund(shop, "refund-" + shop.order());

        JsonNode balance = balanceOf(shop.org());
        assertThat(balance.path("transferRecoveriesMinor").asLong()).isEqualTo(1840);
        assertThat(balance.path("availableMinor").asLong()).as("transferred 88.80 less the 18.40 owed")
                .isEqualTo(shop.net() - 1840);
        assertThat(balance.path("labelChargesMinor").asLong()).as("not double counted").isZero();

        problem(send("POST", "/v1/payouts/requests", Map.of("amountMinor", shop.net()), null), 422,
                "payments.payout-exceeds-available");
        JsonNode request = ok(send("POST", "/v1/payouts/requests", Map.of("all", true), "payout-" + shop.org()), 201);
        assertThat(request.path("amountMinor").asLong()).isEqualTo(shop.net() - 1840);
    }

    @Test
    void laterEarningsAreNettedAgainstTheRecoveryUntilItIsRecovered() {
        Shop first = newShop();
        refusedRefund(first, "refund-" + first.order());
        String recoveryId = jdbc.sql("select id::text from transfer_recovery where order_id = :o")
                .param("o", first.order()).query(String.class).single();

        // A small order: its whole net goes to the recovery, so nothing is transferred and 18.40 - net stays owed.
        Shop small = transferredOrder(id("ORD"), first.org(), first.account(), 1_500, 0, 60);
        assertThat(small.net()).isPositive().isLessThan(1840);
        assertThat(release(small)).as("everything went to the recovery, no transfer").isZero();
        assertThat(jdbc.sql("select count(*) from settlement_transfer where order_id = :o")
                .param("o", small.order()).query(Long.class).single()).isZero();
        JsonNode partly = recovery(recoveryId);
        assertThat(partly.path("status").asString()).isEqualTo("OPEN");
        assertThat(partly.path("recoveredMinor").asLong()).isEqualTo(small.net());
        assertThat(partly.path("outstandingMinor").asLong()).isEqualTo(1840 - small.net());
        assertThat(orgPayable(first.org())).isEqualTo(-(1840 - small.net()));
        assertThat(balance(small.order(), "2000", first.org())).as("the order's payable went to the recovery")
                .isZero();
        assertThat(balanceOf(first.org()).path("transferRecoveriesMinor").asLong()).isEqualTo(1840 - small.net());

        // A big order pays the rest and is transferred in full minus what is still owed.
        Shop big = transferredOrder(id("ORD"), first.org(), first.account(), 10_000, 825, 320);
        assertThat(release(big)).isEqualTo(1);
        long owed = 1840 - small.net();
        LoggedRequest transfer = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/transfers"))
                .withRequestBody(WireMock.containing("transfer_group=" + big.order()))).getFirst();
        assertThat(transfer.getBodyAsString()).contains("amount=" + (big.net() - owed));
        JsonNode done = recovery(recoveryId);
        assertThat(done.path("status").asString()).isEqualTo("RECOVERED");
        assertThat(done.path("recoveredMinor").asLong()).isEqualTo(1840);
        assertThat(done.path("outstandingMinor").asLong()).isZero();
        assertThat(orgPayable(first.org())).as("nothing owed either way").isZero();
        assertThat(balanceOf(first.org()).path("transferRecoveriesMinor").asLong()).isZero();
        assertThat(journals("TRANSFER_RECOVERY_SETTLED", first.org())).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from transfer_recovery_deduction where recovery_id = :r::uuid")
                .param("r", recoveryId).query(Long.class).single()).isEqualTo(2);
        assertEveryJournalBalances();
    }

    // ------------------------------------------------------------------ staff

    @Test
    void financeWritesItOffWithFourEyesAndTheLossLandsOn5300() {
        Shop shop = newShop();
        refusedRefund(shop, "refund-" + shop.order());
        String id = jdbc.sql("select id::text from transfer_recovery where order_id = :o").param("o", shop.order())
                .query(String.class).single();

        TestSupport.as(TestSupport.FINANCE);
        problem(send("POST", "/v1/transfer-recoveries/" + id + "/propose", Map.of("resolution", "WRITE_OFF",
                "reason", WRITE_OFF_REASON), null), 403, "auth.missing-permission");
        TestSupport.as(PROPOSER);
        problem(send("POST", "/v1/transfer-recoveries/" + id + "/propose", Map.of("resolution", "WRITE_OFF",
                "reason", "too short"), null), 422, "payments.reason-required");

        JsonNode proposed = propose(id, "WRITE_OFF", WRITE_OFF_REASON, null);
        assertThat(proposed.path("status").asString()).as("nothing is posted yet").isEqualTo("OPEN");
        assertThat(proposed.path("pending").path("resolution").asString()).isEqualTo("WRITE_OFF");
        assertThat(proposed.path("pending").path("proposedBy").asString()).isEqualTo("fin-proposer");
        assertThat(orgPayable(shop.org())).isEqualTo(-1840);
        problem(send("POST", "/v1/transfer-recoveries/" + id + "/propose", Map.of("resolution", "WRITE_OFF",
                "reason", WRITE_OFF_REASON), null), 409, "payments.recovery-pending");
        problem(send("POST", "/v1/transfer-recoveries/" + id + "/approve", Map.of("note", "ok"), null), 403,
                "payments.recovery-four-eyes");

        TestSupport.as(APPROVER);
        JsonNode closed = ok(send("POST", "/v1/transfer-recoveries/" + id + "/approve", Map.of("note", "Approved"),
                null));
        assertThat(closed.path("status").asString()).isEqualTo("WRITTEN_OFF");
        assertThat(closed.path("resolution").asString()).isEqualTo("WRITE_OFF");
        assertThat(closed.path("decidedBy").asString()).isEqualTo("fin-approver");
        assertThat(closed.path("outstandingMinor").asLong()).isZero();
        assertThat(debits("5300", shop.org())).as("the platform's loss").isEqualTo(1840);
        assertThat(orgPayable(shop.org())).isZero();
        assertThat(journals("TRANSFER_RECOVERY_WRITE_OFF", shop.org())).isEqualTo(1);
        assertThat(balanceOf(shop.org()).path("availableMinor").asLong()).isEqualTo(shop.net());
        TestSupport.as(APPROVER);
        problem(send("POST", "/v1/transfer-recoveries/" + id + "/approve", Map.of("note", "again"), null), 409,
                "payments.recovery-closed");
        assertEveryJournalBalances();

        // Written off for good: later earnings are not touched.
        Shop later = transferredOrder(id("ORD"), shop.org(), shop.account(), 10_000, 825, 320);
        assertThat(release(later)).isEqualTo(1);
        assertThat(WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/transfers"))
                .withRequestBody(WireMock.containing("transfer_group=" + later.order()))).getFirst()
                .getBodyAsString()).contains("amount=" + later.net());
    }

    @Test
    void financeMarksItRecoveredWhenTheSellerPaidOutsideStripeAndRejectionKeepsItOpen() {
        Shop shop = newShop();
        refusedRefund(shop, "refund-" + shop.order());
        String id = jdbc.sql("select id::text from transfer_recovery where order_id = :o").param("o", shop.order())
                .query(String.class).single();

        TestSupport.as(PROPOSER);
        problem(send("POST", "/v1/transfer-recoveries/" + id + "/propose", Map.of("resolution", "RECOVERED",
                "reason", "The seller wired the money on Friday"), null), 422,
                "payments.recovery-reference-required");
        propose(id, "RECOVERED", "The seller wired the money on Friday", "WIRE-4471");
        TestSupport.as(APPROVER);
        problem(send("POST", "/v1/transfer-recoveries/" + id + "/reject", Map.of("note", " "), null), 422,
                "payments.reason-required");
        JsonNode rejected = ok(send("POST", "/v1/transfer-recoveries/" + id + "/reject", Map.of("note",
                "The wire has not arrived"), null));
        assertThat(rejected.path("status").asString()).isEqualTo("OPEN");
        assertThat(rejected.path("pending").isNull() || rejected.path("pending").isMissingNode()).isTrue();
        assertThat(rejected.path("decisionNote").asString()).isEqualTo("The wire has not arrived");
        assertThat(orgPayable(shop.org())).isEqualTo(-1840);

        propose(id, "RECOVERED", "The wire arrived this morning", "WIRE-4472");
        TestSupport.as(APPROVER);
        JsonNode closed = ok(send("POST", "/v1/transfer-recoveries/" + id + "/approve", null, null));
        assertThat(closed.path("status").asString()).isEqualTo("RECOVERED");
        assertThat(closed.path("resolution").asString()).isEqualTo("RECOVERED");
        assertThat(closed.path("resolutionRef").asString()).isEqualTo("WIRE-4472");
        assertThat(debits("1050", shop.org())).as("money received in the operating bank").isEqualTo(1840);
        assertThat(orgPayable(shop.org())).isZero();
        assertThat(journals("TRANSFER_RECOVERY_RECEIVED", shop.org())).isEqualTo(1);
        assertThat(balanceOf(shop.org()).path("transferRecoveriesMinor").asLong()).isZero();
        assertEveryJournalBalances();
    }

    @Test
    void staffListFiltersByStatusAndOrganisation() {
        Shop shop = newShop();
        refusedRefund(shop, "refund-" + shop.order());

        TestSupport.as(PROPOSER);
        JsonNode open = ok(send("GET", "/v1/transfer-recoveries?status=OPEN&orgId=" + shop.org(), null, null));
        assertThat(open).hasSize(1);
        assertThat(open.get(0).path("orgId").asString()).isEqualTo(shop.org());
        assertThat(ok(send("GET", "/v1/transfer-recoveries?status=WRITTEN_OFF&orgId=" + shop.org(), null, null)))
                .isEmpty();
        problem(send("GET", "/v1/transfer-recoveries/" + UUID.randomUUID(), null, null), 404,
                "payments.transfer-recovery-not-found");
        TestSupport.as(TestSupport.seller(shop.org(), "seller.owner", "payouts:request"));
        assertThat(send("GET", "/v1/transfer-recoveries", null, null).statusCode()).as("staff only").isEqualTo(403);
    }
}
