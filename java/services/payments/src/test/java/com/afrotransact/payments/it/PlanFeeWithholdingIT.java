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

import com.afrotransact.payments.application.SettlementService;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S298: seller plan invoices paid from earnings. The seller service withholds an invoice's amount from delivered,
 * undisputed, untransferred nets (partly when they don't cover it, never twice for one invoice); the next transfer is
 * smaller by it with a "Plan fee (invoice …)" deduction; a release pays it back with the following transfer.
 */
class PlanFeeWithholdingIT extends PaymentsIntegrationTest {

    private static final Actor SELLER_SERVICE = new Actor("seller-service", PrincipalType.SERVICE, Optional.empty(),
            Set.of(), Set.of("payments:plan-fees"));
    private static final long NET = 10_000 - 800 - 320;

    private SettlementService settlements;

    @BeforeEach
    void wire(@Autowired SettlementService settlementService) {
        this.settlements = settlementService;
    }

    private static String org(String prefix) {
        return id(prefix).toLowerCase(Locale.ROOT);
    }

    private static String account(String org) {
        return "acct_" + org.replace("-", "").replace("_", "");
    }

    /** One captured order of {@code org} (net {@link #NET}); delivered when asked. */
    private String order(String org, boolean delivered) {
        String order = id("ORD");
        JsonNode payment = createAuthorisedPayment(order, List.of(line("L1", org, "Groceries", "SELF", 1, 10_000, 0,
                825)));
        STRIPE.chargeFees("ch_" + order, new long[] {10_825, 320});
        capture(payment.path("id").asString(), order, order + "-S1", List.of(), 10_825, 320, true);
        if (delivered) {
            TestSupport.as(TestSupport.ORDERS_SERVICE);
            ok(send("POST", "/v1/settlements/" + order + "/delivered", Map.of(), null));
        }
        return order;
    }

    private JsonNode withhold(String org, String invoice, long amount) {
        TestSupport.as(SELLER_SERVICE);
        return ok(send("POST", "/v1/orgs/" + org + "/plan-fee-withholdings", Map.of("invoiceId", invoice,
                "invoiceNumber", "INV-" + invoice, "amountMinor", amount, "currency", "usd"), "plan-fee-" + invoice),
                201);
    }

    private JsonNode release(String org, String invoice, String key, Long amount) {
        TestSupport.as(SELLER_SERVICE);
        Map<String, Object> body = new HashMap<>();
        body.put("reason", "invoice voided");
        if (amount != null) {
            body.put("amountMinor", amount);
        }
        return ok(send("POST", "/v1/orgs/" + org + "/plan-fee-withholdings/" + invoice + "/release", body, key));
    }

    private JsonNode balanceOf(String org) {
        TestSupport.as(TestSupport.seller(org, "seller.owner", "payouts:request"));
        return ok(send("GET", "/v1/payouts/balance", null, null));
    }

    private long journals(String keyPrefix) {
        return jdbc.sql("select count(*) from journal_entry where journal_key like :k").param("k", keyPrefix + "%")
                .query(Long.class).single();
    }

    private long orgBalance(String code, String key, String org) {
        return jdbc.sql("""
                select coalesce(sum(case side when 'C' then amount_minor else -amount_minor end), 0)
                from journal_line where account_code = :c and account_key = :k and org_id = :o
                """).param("c", code).param("k", key).param("o", org).query(Long.class).single();
    }

    private String transferBody(String order) {
        List<LoggedRequest> found = WIREMOCK.findAll(WireMock.postRequestedFor(WireMock.urlPathEqualTo(
                "/v1/transfers")).withRequestBody(WireMock.containing("transfer_group=" + order)));
        assertThat(found).as("one transfer for " + order).hasSize(1);
        return found.getFirst().getBodyAsString();
    }

    @Test
    void aWithheldPlanFeeIsDeductedFromTheNextTransferOnceAndAReleaseIsPaidBackWithTheFollowingOne() {
        String org = org("org_pf");
        String invoice = "in_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        TestSupport.as(TestSupport.FINANCE);
        ok(send("PUT", "/v1/connected-accounts/" + org, Map.of("stripeAccountId", account(org)), null));
        String first = order(org, true);

        // Only the seller service, with plan-fee-<invoice> as the key.
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        assertThat(send("POST", "/v1/orgs/" + org + "/plan-fee-withholdings", Map.of("invoiceId", invoice,
                "amountMinor", 3900), "plan-fee-" + invoice).statusCode()).isEqualTo(403);
        TestSupport.as(SELLER_SERVICE);
        problem(send("POST", "/v1/orgs/" + org + "/plan-fee-withholdings", Map.of("invoiceId", invoice,
                "amountMinor", 3900), null), 422, "payments.idempotency-key-required");
        problem(send("POST", "/v1/orgs/" + org + "/plan-fee-withholdings", Map.of("invoiceId", invoice,
                "amountMinor", 3900), "other-key"), 422, "payments.idempotency-key-required");

        JsonNode w = withhold(org, invoice, 3900);
        assertThat(w.path("status").asString()).isEqualTo("WITHHELD");
        assertThat(w.path("withheldMinor").asLong()).isEqualTo(3900);
        assertThat(w.path("label").asString()).isEqualTo("Plan fee (invoice INV-" + invoice + ")");
        JsonNode replay = withhold(org, invoice, 3900);
        assertThat(replay.path("id").asString()).as("a replay answers the first decision").isEqualTo(
                w.path("id").asString());
        assertThat(journals("plan-fee:" + invoice)).as("withheld once").isEqualTo(1);
        problem(send("POST", "/v1/orgs/" + org + "/plan-fee-withholdings", Map.of("invoiceId", invoice,
                "amountMinor", 4900), "plan-fee-" + invoice), 409, "payments.idempotency-conflict");
        assertThat(orgBalance("4600", "", org)).isEqualTo(3900);
        assertEveryJournalBalances();

        // The fee comes out of the amounts on hold, not out of available.
        JsonNode balance = balanceOf(org);
        assertThat(balance.path("planFeesMinor").asLong()).isEqualTo(3900);
        assertThat(balance.path("onHoldMinor").asLong()).isEqualTo(NET);
        assertThat(balance.path("availableMinor").asLong()).isZero();
        JsonNode statement = ok(send("GET", "/v1/payouts/statement?kind=PLAN_FEE_WITHHELD", null, null));
        assertThat(statement.size()).isEqualTo(1);
        assertThat(statement.get(0).path("amountMinor").asLong()).isEqualTo(-3900);
        assertThat(statement.get(0).path("description").asString()).isEqualTo("Plan fee (invoice INV-" + invoice
                + ")");
        assertThat(ok(send("GET", "/v1/orgs/" + org + "/plan-fee-withholdings", null, null)).size())
                .as("members see their own plan fees").isEqualTo(1);

        // The next transfer is smaller by the fee, with the deduction line.
        STRIPE.transfer(first, account(org), "tr_" + first, NET - 3900);
        TestSupport.CLOCK.advance(Duration.ofDays(8));
        settlements.releaseMatured(500);
        assertThat(transferBody(first)).contains("amount=" + (NET - 3900));
        assertThat(balance(first, "2000", org)).isZero();
        TestSupport.as(SELLER_SERVICE);
        JsonNode listed = ok(send("GET", "/v1/orgs/" + org + "/plan-fee-withholdings", null, null)).get(0);
        assertThat(listed.path("settledMinor").asLong()).isEqualTo(3900);
        assertThat(listed.path("outstandingMinor").asLong()).isZero();
        assertThat(listed.path("deductions").get(0).path("orderId").asString()).isEqualTo(first);
        assertThat(listed.path("deductions").get(0).path("amountMinor").asLong()).isEqualTo(3900);
        assertThat(listed.path("deductions").get(0).path("label").asString())
                .isEqualTo("Plan fee (invoice INV-" + invoice + ") deducted from order " + first);
        balance = balanceOf(org);
        assertThat(balance.path("planFeesMinor").asLong()).isZero();
        assertThat(balance.path("availableMinor").asLong()).isEqualTo(NET - 3900);
        assertEveryJournalBalances();

        // Voided after it was recovered: released once, paid back with the following transfer.
        String key = "plan-fee-release-" + invoice;
        JsonNode released = release(org, invoice, key, null);
        assertThat(released.path("status").asString()).isEqualTo("RELEASED");
        assertThat(released.path("releasedMinor").asLong()).isEqualTo(3900);
        assertThat(released.path("outstandingMinor").asLong()).isEqualTo(-3900);
        assertThat(release(org, invoice, key, null).path("releasedMinor").asLong()).isEqualTo(3900);
        assertThat(release(org, invoice, "plan-fee-release-again-" + invoice, null).path("releasedMinor").asLong())
                .as("nothing left to release under another key").isEqualTo(3900);
        assertThat(journals("plan-fee-release:" + key)).as("a replayed release posts once").isEqualTo(1);
        assertThat(journals("plan-fee-release:plan-fee-release-again-" + invoice)).isZero();
        assertThat(orgBalance("4600", "", org)).isZero();
        assertThat(balanceOf(org).path("planFeesMinor").asLong()).isEqualTo(-3900);

        String second = order(org, true);
        STRIPE.transfer(second, account(org), "tr_" + second, NET + 3900);
        TestSupport.CLOCK.advance(Duration.ofDays(8));
        settlements.releaseMatured(500);
        assertThat(transferBody(second)).contains("amount=" + (NET + 3900));
        assertThat(balanceOf(org).path("planFeesMinor").asLong()).isZero();
        assertThat(orgBalance("2000", org, org)).isZero();
        assertEveryJournalBalances();
    }

    @Test
    void onlyDeliveredUndisputedEarningsAreWithheldPartlyWhenShortAndNeverTwice() {
        String org = org("org_pp");
        String pending = order(org, false);
        JsonNode undelivered = withhold(org, "in_" + id("u"), 1000);
        assertThat(undelivered.path("status").asString()).as("not delivered yet").isEqualTo("DECLINED");
        assertThat(undelivered.path("withheldMinor").asLong()).isZero();
        assertThat(undelivered.path("reason").asString()).isNotBlank();

        // Delivered, but under an open dispute: excluded entirely.
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/settlements/" + pending + "/delivered", Map.of(), null));
        String dispute = "{\"id\":\"dp_" + pending + "\",\"object\":\"dispute\",\"amount\":2000,\"currency\":\"usd\","
                + "\"charge\":\"ch_" + pending + "\",\"payment_intent\":\"pi_" + pending + "\",\"reason\":"
                + "\"fraudulent\",\"status\":\"needs_response\",\"balance_transactions\":[{\"id\":\"txn_d\","
                + "\"fee\":1500}]}";
        webhook(event(id("evt"), "charge.dispute.created", dispute));
        assertThat(balance(pending, "2000", org)).isPositive();
        assertThat(withhold(org, "in_" + id("d"), 1000).path("status").asString()).as("open dispute")
                .isEqualTo("DECLINED");

        // Short: withhold what is there, the card pays the rest.
        order(org, true);
        String partial = "in_" + id("p");
        JsonNode p = withhold(org, partial, NET + 2500);
        assertThat(p.path("status").asString()).isEqualTo("WITHHELD");
        assertThat(p.path("withheldMinor").asLong()).isEqualTo(NET);
        assertThat(p.path("requestedMinor").asLong()).isEqualTo(NET + 2500);
        assertThat(p.path("reason").asString()).contains("card");
        // The same earnings are never claimed twice.
        assertThat(withhold(org, "in_" + id("x"), 500).path("status").asString()).isEqualTo("DECLINED");
        assertThat(balanceOf(org).path("planFeesMinor").asLong()).isEqualTo(NET);

        // A partial refund (credit note), then the rest: nothing is left to deduct.
        JsonNode some = release(org, partial, "plan-fee-release-cn_" + partial, 500L);
        assertThat(some.path("status").asString()).isEqualTo("WITHHELD");
        assertThat(some.path("releasedMinor").asLong()).isEqualTo(500);
        JsonNode rest = release(org, partial, "plan-fee-release-" + partial, null);
        assertThat(rest.path("status").asString()).isEqualTo("RELEASED");
        assertThat(rest.path("releasedMinor").asLong()).isEqualTo(NET);
        assertThat(rest.path("outstandingMinor").asLong()).isZero();
        assertThat(balanceOf(org).path("planFeesMinor").asLong()).isZero();
        assertThat(orgBalance("4600", "", org)).isZero();
        assertEveryJournalBalances();

        TestSupport.as(SELLER_SERVICE);
        assertThat(send("POST", "/v1/orgs/" + org + "/plan-fee-withholdings/in_unknown/release", Map.of(),
                "plan-fee-release-in_unknown").statusCode()).isEqualTo(404);
        TestSupport.as(TestSupport.seller(org, "seller.owner", "payouts:request"));
        assertThat(send("POST", "/v1/orgs/" + org + "/plan-fee-withholdings/" + partial + "/release", Map.of(),
                "plan-fee-release-x").statusCode()).as("sellers can't release their own fees").isEqualTo(403);
    }

    @Test
    void afroTransactRetailIsNeverWithheld() {
        JsonNode w = withhold(RETAIL, "in_" + id("r"), 1000);
        assertThat(w.path("status").asString()).isEqualTo("DECLINED");
    }
}
