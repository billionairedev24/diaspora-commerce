package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.afrotransact.seller.application.BillingService;
import com.afrotransact.seller.application.PlanFeePaymentService;
import com.afrotransact.seller.application.SellerEvents;
import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.domain.Cadence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Plan-fee payments after the charge, with one platform webhook (payments hands plan-fee events to seller): 3-D Secure
 * off-session (Action needed, Confirm payment, settled by the event), a late bank-debit return, disputes won and lost,
 * staff refunds (partial, full, retried) and a refund made in the Stripe dashboard.
 */
class PlanFeePaymentsIT extends SellerIntegrationTest {

    private BillingService billing;
    private PlanFeePaymentService planFees;

    @BeforeEach
    void wire(@Autowired BillingService billingService, @Autowired PlanFeePaymentService planFeeService) {
        this.billing = billingService;
        this.planFees = planFeeService;
    }

    @Test
    void aChargeThatNeedsThreeDSecureIsConfirmedBySellerAndSettledByTheEvent() {
        String owner = unique("tds");
        String org = approvedSeller(owner, "seller_growth", Cadence.MONTHLY);
        TestActors.as(TestActors.applicant(owner));
        post("/v1/orgs/" + org + "/billing/payment-methods", Map.of("stripePaymentMethodId", "pm_3ds_1", "kind", "CARD",
                "brand", "visa", "last4", "3155", "expMonth", 8, "expYear", 2031, "makeDefault", true));
        String invoiceId = charge(org);

        JsonNode inv = invoice(org);
        assertThat(inv.path("status").asString()).isEqualTo("open");
        assertThat(inv.path("actionRequired").asBoolean()).isTrue();
        assertThat(inv.path("lastError").asString()).contains("Confirm payment");
        assertThat(lastEvent(SellerEvents.BILLING_PAYMENT_ACTION_REQUIRED, org).path("invoiceId").asString())
                .isEqualTo(invoiceId);

        Response confirm = post("/v1/orgs/" + org + "/billing/invoices/" + invoiceId + "/confirm-payment", Map.of());
        assertThat(confirm.status()).isEqualTo(200);
        assertThat(confirm.text("/clientSecret")).isEqualTo("pi_3ds_1_secret_test");
        assertThat(confirm.text("/paymentMethodId")).isEqualTo("pm_3ds_1");

        assertThat(planFees.apply(PlanFeeEvents.intent(unique("evt"), "payment_intent.succeeded", "pi_3ds_1",
                invoiceId))).isEqualTo("settled");
        assertThat(invoice(org).path("status").asString()).isEqualTo("paid");
        assertThat(post("/v1/orgs/" + org + "/billing/invoices/" + invoiceId + "/confirm-payment", Map.of()).status())
                .isEqualTo(409);
    }

    @Test
    void aBankDebitReturnedAfterItSucceededReopensTheInvoiceAndRestartsDunning() {
        String owner = unique("ach");
        String org = approvedSeller(owner, "seller_growth", Cadence.MONTHLY);
        TestActors.as(TestActors.applicant(owner));
        post("/v1/orgs/" + org + "/billing/payment-methods", Map.of("stripePaymentMethodId", "pm_ach_1", "kind",
                "US_BANK_ACCOUNT", "bankName", "STRIPE TEST BANK", "accountType", "checking", "last4", "6789",
                "setupIntentId", "seti_verified_" + owner.replace("-", ""), "makeDefault", true));
        String invoiceId = charge(org);
        String intent = intentOf(invoiceId);
        assertThat(planFees.apply(PlanFeeEvents.intent(unique("evt"), "payment_intent.succeeded", intent, invoiceId)))
                .isEqualTo("settled");
        assertThat(invoice(org).path("status").asString()).isEqualTo("paid");

        assertThat(planFees.apply(PlanFeeEvents.failed(unique("evt"), "payment_intent.payment_failed", intent, invoiceId,
                "insufficient_funds", "Insufficient funds"))).isEqualTo("failed");
        JsonNode reopened = invoice(org);
        assertThat(reopened.path("status").asString()).isEqualTo("open");
        assertThat(reopened.path("amountPaidCents").asLong()).isZero();
        assertThat(reopened.path("lastError").asString()).contains("returned");
        assertThat(column(org, "status")).isEqualTo("PAST_DUE");
        assertThat(column(org, "dunning")).isEqualTo("RETRYING");
    }

    @Test
    void aLostDisputeReopensTheAmountAndAWonOneJustCloses() {
        String owner = unique("dsp");
        String org = approvedSeller(owner, "seller_growth", Cadence.MONTHLY);
        TestActors.as(TestActors.applicant(owner));
        String invoiceId = charge(org);
        String intent = intentOf(invoiceId);
        assertThat(invoice(org).path("status").asString()).isEqualTo("paid");

        String won = unique("dp");
        assertThat(planFees.apply(PlanFeeEvents.dispute(unique("evt"), "charge.dispute.created", intent, won,
                "needs_response", 3900))).isEqualTo("dispute-opened");
        TestActors.as(TestActors.staff(unique("fin"), SellerPermissions.BILLING));
        assertThat(get("/v1/billing/disputes?outcome=OPEN").body().valueStream()
                .map(d -> d.path("stripeDisputeId").asString())).contains(won);
        assertThat(planFees.apply(PlanFeeEvents.dispute(unique("evt"), "charge.dispute.closed", intent, won, "won",
                3900))).isEqualTo("dispute-won");
        assertThat(invoice(org).path("status").asString()).as("a won dispute changes nothing").isEqualTo("paid");

        String lost = unique("dp");
        planFees.apply(PlanFeeEvents.dispute(unique("evt"), "charge.dispute.created", intent, lost, "needs_response",
                3900));
        assertThat(planFees.apply(PlanFeeEvents.dispute(unique("evt"), "charge.dispute.closed", intent, lost, "lost",
                3900))).isEqualTo("dispute-lost");
        assertThat(invoice(org).path("status").asString()).isEqualTo("open");
        assertThat(column(org, "status")).isEqualTo("PAST_DUE");
        assertThat(get("/v1/orgs/" + org + "/billing/invoices/" + invoiceId + "/adjustments").body().path("disputes")
                .valueStream().map(d -> d.path("outcome").asString())).containsExactly("WON", "LOST");
    }

    @Test
    void staffRefundPartiallyThenFullyAndADashboardRefundIsReconciled() {
        String owner = unique("ref");
        String org = approvedSeller(owner, "seller_growth", Cadence.MONTHLY);
        TestActors.as(TestActors.applicant(owner));
        String invoiceId = charge(org);
        String intent = intentOf(invoiceId);
        long paid = invoice(org).path("amountPaidCents").asLong();

        TestActors.as(TestActors.staff(unique("viewer"), SellerPermissions.BILLING));
        assertThat(post("/v1/orgs/" + org + "/billing/invoices/" + invoiceId + "/refunds", Map.of("amountCents", 100,
                "reasonCode", "GOODWILL")).status()).as("billing:adjust only").isEqualTo(403);

        TestActors.as(TestActors.staff(unique("adjust"), SellerPermissions.BILLING_ADJUST, SellerPermissions.BILLING));
        String path = "/v1/orgs/" + org + "/billing/invoices/" + invoiceId + "/refunds";
        Response partial = post(path, Map.of("amountCents", 1000, "reasonCode", "GOODWILL"), "rk-1");
        assertThat(partial.status()).isEqualTo(201);
        assertThat(partial.text("/status")).isEqualTo("SUCCEEDED");
        Response retried = post(path, Map.of("amountCents", 1000, "reasonCode", "GOODWILL"), "rk-1");
        assertThat(retried.text("/id")).as("same Idempotency-Key, same refund").isEqualTo(partial.text("/id"));
        assertThat(StripeStub.requests(STRIPE, "POST", "/v1/refunds")).anySatisfy(r -> assertThat(r.getHeader(
                "Idempotency-Key")).isEqualTo("plan-refund-" + invoiceId + "-rk-1"));
        assertThat(post(path, Map.of("amountCents", paid, "reasonCode", "GOODWILL"), "rk-2").status())
                .as("more than is left").isEqualTo(422);
        assertThat(post(path, Map.of("amountCents", paid - 1500, "reasonCode", "OTHER", "note",
                "Charged during an outage"), "rk-3").status()).isEqualTo(201);

        // 500 more refunded in the Stripe dashboard: charge.refunded carries the cumulative amount.
        assertThat(planFees.apply(PlanFeeEvents.refunded(unique("evt"), intent, "ch_" + invoiceId, paid)))
                .isEqualTo("refund-reconciled");
        JsonNode adjustments = get("/v1/orgs/" + org + "/billing/invoices/" + invoiceId + "/adjustments").body();
        assertThat(adjustments.path("refundedCents").asLong()).isEqualTo(paid);
        assertThat(adjustments.path("refunds").valueStream().map(r -> r.path("origin").asString()))
                .containsExactly("STAFF", "STAFF", "DASHBOARD");
        assertThat(invoice(org).path("status").asString()).as("a refund is a credit, the invoice stays paid")
                .isEqualTo("paid");
    }

    /** Ends the trial and renews: one invoice, charged to the default method. Returns its id. */
    private String charge(String org) {
        jdbc.sql("""
                update subscription set trial_ends_at = case when trial_ends_at is null then null
                           else now() - interval '1 hour' end,
                       current_period_end = now() - interval '1 hour', anchor_date = now() - interval '1 hour'
                where org_id = :o
                """).param("o", org).update();
        billing.renewDueSubscriptions();
        return invoice(org).path("id").asString();
    }

    private JsonNode invoice(String org) {
        return get("/v1/orgs/" + org + "/billing/invoices").body().get(0);
    }

    private String intentOf(String invoiceId) {
        return jdbc.sql("select reference from invoice_payment where invoice_id = :i and source = 'CARD' "
                + "order by created_at desc limit 1").param("i", invoiceId).query(String.class).single();
    }

    private String column(String org, String column) {
        return jdbc.sql("select " + column + "::text from subscription where org_id = :o").param("o", org)
                .query(String.class).optional().orElse(null);
    }
}
