package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import com.afrotransact.seller.application.BillingService;
import com.afrotransact.seller.application.InvoiceCollectionService;
import com.afrotransact.seller.application.SellerEvents;
import com.afrotransact.seller.domain.Cadence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S103–S104: AfroTransact bills plans itself. Periods follow the subscription's RRULE, invoices are ours, credit then
 * (opt-in) earnings then the saved card pay them, and failed charges retry on a schedule.
 */
class BillingIT extends SellerIntegrationTest {

    private static final String BILLING = "/v1/webhooks/stripe-billing";

    private BillingService billing;
    private InvoiceCollectionService collection;

    @BeforeEach
    void wire(@Autowired BillingService billingService, @Autowired InvoiceCollectionService collectionService) {
        this.billing = billingService;
        this.collection = collectionService;
    }

    @Test
    void planCatalogueHasEveryCadence() {
        Response plans = get("/v1/plans?kind=PROVIDER");
        assertThat(plans.status()).isEqualTo(200);
        assertThat(plans.body().valueStream().map(p -> p.path("name").asString()))
                .containsExactly("Provider Solo", "Provider Studio", "Provider Business");
        JsonNode studio = plans.body().get(1);
        JsonNode quarterly = studio.path("prices").get(1);
        assertThat(quarterly.path("cadence").asString()).isEqualTo("QUARTERLY");
        assertThat(quarterly.path("totalCents").asLong()).isEqualTo(13965);
        assertThat(quarterly.path("perMonthLabel").asString()).isEqualTo("$46.55/mo");
        assertThat(quarterly.path("billedLabel").asString()).isEqualTo("Billed $139.65 every 3 months");
        assertThat(quarterly.path("savingLabel").asString()).isEqualTo("Save $7.35 vs monthly");
        assertThat(studio.path("prices").get(2).path("totalCents").asLong()).isEqualTo(49000);
        assertThat(get("/v1/plans").body().valueStream().map(p -> p.path("code").asString()))
                .contains("seller_essentials", "seller_growth", "seller_pro", "provider_solo", "provider_studio",
                        "provider_business");
        assertThat(get("/v1/plans?kind=SELLER").body().get(0).at("/prices/0/perMonthLabel").asString())
                .isEqualTo("Free");
    }

    @Test
    void theTrialEndsThenTheFirstInvoiceIsIssuedAndPaidByCard() {
        String org = approvedSeller(unique("first"), "seller_growth", Cadence.MONTHLY);
        assertThat(column(org, "status")).isEqualTo("TRIALING");
        assertThat(column(org, "trial_ends_at")).isNotNull();
        assertThat(column(org, "anchor_date")).as("the RRULE restarts when the trial ends").isNotNull();
        assertThat(invoiceCount(org)).as("nothing is charged during the trial").isZero();

        endPeriodNow(org);
        assertThat(billing.renewDueSubscriptions()).isGreaterThanOrEqualTo(1);

        assertThat(column(org, "status")).isEqualTo("ACTIVE");
        assertThat(invoiceCount(org)).isEqualTo(1);
        assertThat(jdbc.sql("select status || ':' || amount_due_cents || ':' || amount_paid_cents || ':' || "
                + "billing_reason from invoice where org_id = :o").param("o", org).query(String.class).single())
                .isEqualTo("paid:3900:3900:subscription_cycle");
        assertThat(jdbc.sql("select source || ':' || status || ':' || amount_cents from invoice_payment where org_id = "
                + ":o").param("o", org).query(String.class).list()).containsExactly("CARD:APPLIED:3900");
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/payment_intents")).anySatisfy(b -> assertThat(b)
                .contains("amount=3900", "currency=usd", "off_session=true", "confirm=true",
                        "metadata[purpose]=plan_fee", "metadata[invoice_id]=inv_"));
        assertThat(jdbc.sql("select count(*) from billing_customer where org_id = :o").param("o", org)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select (current_period_end > now() + interval '27 days') from subscription where org_id "
                + "= :o").param("o", org).query(Boolean.class).single()).as("one month on").isTrue();
        assertThat(lastEvent(SellerEvents.SUBSCRIPTION_CHANGED, org).path("change").asString())
                .isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(billing.renewDueSubscriptions()).as("renewing twice does not invoice twice").isZero();
    }

    @Test
    void quarterlyAndYearlyPlansAreInvoicedForTheirWholePeriod() {
        String quarterly = approvedSeller(unique("q"), "seller_growth", Cadence.QUARTERLY);
        String yearly = approvedSeller(unique("y"), "seller_growth", Cadence.ANNUAL);
        endPeriodNow(quarterly);
        endPeriodNow(yearly);
        billing.renewDueSubscriptions();
        assertThat(jdbc.sql("select amount_due_cents from invoice where org_id = :o").param("o", quarterly)
                .query(Long.class).single()).isEqualTo(11115);
        assertThat(jdbc.sql("select amount_due_cents from invoice where org_id = :o").param("o", yearly)
                .query(Long.class).single()).isEqualTo(39000);
        assertThat(jdbc.sql("select (current_period_end > now() + interval '85 days') from subscription where "
                + "org_id = :o").param("o", quarterly).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select (current_period_end > now() + interval '360 days') from subscription where "
                + "org_id = :o").param("o", yearly).query(Boolean.class).single()).isTrue();
    }

    @Test
    void upgradesInvoiceTheDifferenceNowDowngradesAndCadenceChangesWaitForRenewal() {
        String owner = unique("bill");
        String org = approvedSeller(owner, "seller_growth", Cadence.MONTHLY);
        endPeriodNow(org);
        billing.renewDueSubscriptions();
        assertThat(column(org, "status")).isEqualTo("ACTIVE");

        TestActors.as(TestActors.member(unique("fin"), org, "seller.finance"));
        assertThat(get("/v1/orgs/" + org + "/billing").status()).isEqualTo(200);
        assertThat(post("/v1/orgs/" + org + "/billing/plan", Map.of("planCode", "seller_pro")).status())
                .isEqualTo(403);

        TestActors.as(TestActors.applicant(owner));
        Response overview = get("/v1/orgs/" + org + "/billing");
        assertThat(overview.text("/planName")).isEqualTo("Seller Growth");
        assertThat(overview.text("/nextChargeLabel")).startsWith("Next charge $39 on ").endsWith("to Visa ••4242");

        // The owner sees what an upgrade costs now before confirming it: the price difference for the rest of the
        // period. A downgrade or cadence change would only apply at renewal. Nothing changes.
        int paymentsBefore = StripeStub.requests(STRIPE, "POST", "/v1/payment_intents").size();
        Response preview = get("/v1/orgs/" + org + "/billing/plan/preview?planCode=seller_pro&cadence=MONTHLY");
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.text("/change")).isEqualTo("IMMEDIATE_UPGRADE");
        long previewed = preview.body().path("amountDueNowCents").asLong();
        assertThat(previewed).isBetween(8500L, 9000L);
        Response later = get("/v1/orgs/" + org + "/billing/plan/preview?planCode=seller_growth&cadence=ANNUAL");
        assertThat(later.text("/change")).isEqualTo("AT_RENEWAL");
        assertThat(later.body().path("amountDueNowCents").asLong()).isZero();
        assertThat(StripeStub.requests(STRIPE, "POST", "/v1/payment_intents")).hasSize(paymentsBefore);

        Response upgrade = post("/v1/orgs/" + org + "/billing/plan", Map.of("planCode", "seller_pro",
                "cadence", "MONTHLY"));
        assertThat(upgrade.status()).isEqualTo(200);
        assertThat(upgrade.text("/applied")).isEqualTo("IMMEDIATE_UPGRADE");
        assertThat(upgrade.text("/billing/planCode")).isEqualTo("seller_pro");
        assertThat(jdbc.sql("select amount_due_cents from invoice where org_id = :o and billing_reason = "
                + "'subscription_update'").param("o", org).query(Long.class).single())
                .isBetween(8500L, 9000L);
        assertThat(StripeStub.requests(STRIPE, "POST", "/v1/payment_intents")).hasSize(paymentsBefore + 1);
        assertThat(lastEvent(SellerEvents.SUBSCRIPTION_CHANGED, org).path("change").asString())
                .isIn("UPGRADED", "PAYMENT_SUCCEEDED");

        Response downgrade = post("/v1/orgs/" + org + "/billing/plan", Map.of("planCode", "seller_growth",
                "cadence", "MONTHLY"));
        assertThat(downgrade.text("/applied")).isEqualTo("AT_RENEWAL");
        assertThat(downgrade.text("/billing/planCode")).isEqualTo("seller_pro");
        assertThat(downgrade.text("/billing/pendingChange/planCode")).isEqualTo("seller_growth");
        assertThat(downgrade.text("/billing/nextChargeLabel")).startsWith("Next charge $39 on ");
        assertThat(StripeStub.requests(STRIPE, "POST", "/v1/payment_intents")).hasSize(paymentsBefore + 1);

        Response cadence = post("/v1/orgs/" + org + "/billing/plan", Map.of("planCode", "seller_pro",
                "cadence", "ANNUAL"));
        assertThat(cadence.text("/applied")).isEqualTo("AT_RENEWAL");
        assertThat(cadence.text("/billing/pendingChange/cadence")).isEqualTo("ANNUAL");
        Response keep = post("/v1/orgs/" + org + "/billing/plan", Map.of("planCode", "seller_pro",
                "cadence", "MONTHLY"));
        assertThat(keep.text("/applied")).isEqualTo("NO_CHANGE");
        assertThat(keep.body().path("billing").path("pendingChange").isNull()).isTrue();

        post("/v1/orgs/" + org + "/billing/plan", Map.of("planCode", "seller_growth", "cadence", "MONTHLY"));
        endPeriodNow(org);
        assertThat(billing.renewDueSubscriptions()).isGreaterThanOrEqualTo(1);
        assertThat(column(org, "plan_code")).isEqualTo("seller_growth");
        assertThat(column(org, "pending_plan_code")).isNull();
        assertThat(jdbc.sql("select amount_due_cents from invoice where org_id = :o and billing_reason = "
                + "'subscription_cycle' order by created_at desc limit 1").param("o", org).query(Long.class)
                .single()).as("the renewal is invoiced at the new plan").isEqualTo(3900);

        Response cancel = post("/v1/orgs/" + org + "/billing/cancel", Map.of());
        assertThat(cancel.body().path("cancelAtPeriodEnd").asBoolean()).isTrue();
        assertThat(cancel.text("/nextChargeLabel")).startsWith("Ends ");
        assertThat(post("/v1/orgs/" + org + "/billing/resume", Map.of()).body().path("cancelAtPeriodEnd")
                .asBoolean()).isFalse();
        post("/v1/orgs/" + org + "/billing/cancel", Map.of());
        endPeriodNow(org);
        billing.renewDueSubscriptions();
        assertThat(column(org, "status")).as("cancelled at the end of the period, no further invoice")
                .isEqualTo("CANCELED");
    }

    @Test
    void paymentMethodsDeclinesDunningAndRetry() {
        String owner = unique("pm");
        String org = approvedSeller(owner, "seller_pro", Cadence.QUARTERLY);
        TestActors.as(TestActors.applicant(owner));

        Response setup = post("/v1/orgs/" + org + "/billing/setup-intents", Map.of());
        assertThat(setup.status()).isEqualTo(201);
        assertThat(setup.text("/clientSecret")).contains("_secret_");
        String customer = setup.text("/customerId");
        assertThat(customer).startsWith("cus_");

        Response methods = get("/v1/orgs/" + org + "/billing/payment-methods");
        assertThat(methods.body().size()).isEqualTo(1);
        String original = methods.body().get(0).path("id").asString();
        assertThat(methods.body().get(0).path("isDefault").asBoolean()).isTrue();
        assertThat(methods.body().get(0).path("label").asString()).isEqualTo("Visa ••4242");

        Response expired = post("/v1/orgs/" + org + "/billing/payment-methods", Map.of("stripePaymentMethodId",
                "pm_old", "kind", "CARD", "brand", "visa", "last4", "1111", "expMonth", 1, "expYear", 2020));
        assertThat(expired.status()).isEqualTo(422);
        Response declining = post("/v1/orgs/" + org + "/billing/payment-methods", Map.of("stripePaymentMethodId",
                "pm_decline_1", "kind", "CARD", "brand", "mastercard", "last4", "0002", "expMonth", 8,
                "expYear", 2031));
        assertThat(declining.status()).isEqualTo(201);
        assertThat(declining.body().path("isDefault").asBoolean()).isFalse();
        Response bank = post("/v1/orgs/" + org + "/billing/payment-methods", Map.of("stripePaymentMethodId",
                "pm_ach_1", "kind", "US_BANK_ACCOUNT", "bankName", "Chase", "accountType", "checking",
                "last4", "6789"));
        assertThat(bank.body().path("label").asString()).isEqualTo("Chase checking ••6789");

        Response refuse = delete("/v1/orgs/" + org + "/billing/payment-methods/" + original);
        assertThat(refuse.status()).isEqualTo(409);
        assertThat(refuse.text("/code")).isEqualTo("seller.default-payment-method");

        TestActors.as(TestActors.member(unique("mkt"), org, "seller.marketing"));
        assertThat(get("/v1/orgs/" + org + "/billing/invoices").status()).isEqualTo(403);
        TestActors.as(TestActors.applicant(owner));

        // The default method is declined: the invoice stays open, the seller is past due, and a retry is scheduled.
        String declined = declining.body().path("id").asString();
        post("/v1/orgs/" + org + "/billing/payment-methods/" + declined + "/default", Map.of());
        endPeriodNow(org);
        billing.renewDueSubscriptions();
        assertThat(column(org, "status")).isEqualTo("PAST_DUE");
        assertThat(column(org, "dunning")).isEqualTo("RETRYING");
        assertThat(lastEvent(SellerEvents.SUBSCRIPTION_CHANGED, org).path("change").asString())
                .isEqualTo("PAYMENT_FAILED");
        Response invoices = get("/v1/orgs/" + org + "/billing/invoices");
        assertThat(invoices.body().size()).isEqualTo(1);
        JsonNode failed = invoices.body().get(0);
        assertThat(failed.path("status").asString()).isEqualTo("open");
        assertThat(failed.path("amountDueCents").asLong()).isEqualTo(36765);
        assertThat(failed.path("attempts").asInt()).isEqualTo(1);
        assertThat(failed.path("lastError").asString()).isEqualTo("Your card was declined.");
        assertThat(failed.path("nextAttemptAt").isNull()).isFalse();
        String invoiceId = failed.path("id").asString();

        // The seller makes the bank account the default; the scheduled retry debits it (ACH settles later).
        post("/v1/orgs/" + org + "/billing/payment-methods/" + bank.body().path("id").asString() + "/default",
                Map.of());
        jdbc.sql("update invoice set next_attempt_at = now() - interval '1 minute' where stripe_invoice_id = :i")
                .param("i", invoiceId).update();
        assertThat(collection.collectDue()).isZero();
        assertThat(jdbc.sql("select source || ':' || status from invoice_payment where invoice_id = :i and status = "
                + "'PENDING'").param("i", invoiceId).query(String.class).list()).containsExactly("CARD:PENDING");
        assertThat(column(org, "status")).as("still past due until the debit settles").isEqualTo("PAST_DUE");

        String intent = jdbc.sql("select reference from invoice_payment where invoice_id = :i and status = 'PENDING'")
                .param("i", invoiceId).query(String.class).single();
        String settled = StripeEvents.event(unique("evt"), "payment_intent.succeeded",
                System.currentTimeMillis() / 1000, null, StripeEvents.planPayment(intent, invoiceId, null));
        assertThat(webhook(BILLING, settled, sign(settled, BILLING_SECRET)).text("/outcome"))
                .isEqualTo("plan-payment");
        assertThat(webhook(BILLING, settled, sign(settled, BILLING_SECRET)).text("/outcome")).isEqualTo("duplicate");
        assertThat(webhook(BILLING, settled, sign(settled, CONNECT_SECRET)).status()).as("connect secret on billing")
                .isEqualTo(400);
        assertThat(column(org, "status")).isEqualTo("ACTIVE");
        assertThat(column(org, "dunning")).isEqualTo("NONE");
        JsonNode paid = get("/v1/orgs/" + org + "/billing/invoices").body().get(0);
        assertThat(paid.path("status").asString()).isEqualTo("paid");
        assertThat(paid.path("amountPaidCents").asLong()).isEqualTo(36765);
        assertThat(paid.path("paidByCardCents").asLong()).isEqualTo(36765);

        // Removing a non-default method detaches it at Stripe.
        Response removed = delete("/v1/orgs/" + org + "/billing/payment-methods/" + original);
        assertThat(removed.status()).isEqualTo(200);
        assertThat(StripeStub.requests(STRIPE, "POST", "/v1/payment_methods/pm_card_" + owner.replace("-", "")
                + "/detach")).hasSize(1);
        assertThat(customer).isEqualTo(jdbc.sql("select stripe_customer_id from billing_customer where org_id = :o")
                .param("o", org).query(String.class).single());
    }

    @Test
    void anInvoiceHasAPrintableDocumentAndAnUncollectibleOneCanBeRetried() throws Exception {
        String owner = unique("doc");
        String org = approvedSeller(owner, "seller_growth", Cadence.MONTHLY);
        TestActors.as(TestActors.applicant(owner));
        String declined = post("/v1/orgs/" + org + "/billing/payment-methods", Map.of("stripePaymentMethodId",
                "pm_decline_2", "kind", "CARD", "brand", "visa", "last4", "0002", "expMonth", 8, "expYear", 2031,
                "makeDefault", true)).body().path("id").asString();
        assertThat(declined).isNotBlank();
        endPeriodNow(org);
        billing.renewDueSubscriptions();
        String invoiceId = get("/v1/orgs/" + org + "/billing/invoices").body().get(0).path("id").asString();

        // Every attempt fails: the invoice becomes uncollectible and the subscription unpaid.
        for (int i = 0; i < 4; i++) {
            jdbc.sql("update invoice set next_attempt_at = now() - interval '1 minute' where stripe_invoice_id = :i")
                    .param("i", invoiceId).update();
            collection.collectDue();
        }
        assertThat(column(org, "status")).isEqualTo("UNPAID");
        assertThat(column(org, "dunning")).isEqualTo("FINAL_ATTEMPT_FAILED");
        assertThat(get("/v1/orgs/" + org + "/billing/invoices").body().get(0).path("status").asString())
                .isEqualTo("uncollectible");

        HttpResponse<String> doc = HTTP.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + port + "/v1/orgs/" + org + "/billing/invoices/" + invoiceId + "/document"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(doc.statusCode()).isEqualTo(200);
        assertThat(doc.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(doc.body()).contains("AT-INV-").contains("Seller Growth plan").contains("$39");

        // The seller fixes their card and retries: the invoice is paid and the subscription is in good standing.
        String good = post("/v1/orgs/" + org + "/billing/payment-methods", Map.of("stripePaymentMethodId",
                "pm_card_good", "kind", "CARD", "brand", "visa", "last4", "4242", "expMonth", 8, "expYear", 2031,
                "makeDefault", true)).body().path("id").asString();
        post("/v1/orgs/" + org + "/billing/payment-methods/" + good + "/default", Map.of());
        Response retried = post("/v1/orgs/" + org + "/billing/invoices/" + invoiceId + "/retry", Map.of());
        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.text("/status")).isEqualTo("paid");
        assertThat(column(org, "status")).isEqualTo("ACTIVE");
    }

    private void endPeriodNow(String org) {
        jdbc.sql("""
                update subscription set trial_ends_at = case when trial_ends_at is null then null
                           else now() - interval '1 hour' end,
                       current_period_end = now() - interval '1 hour',
                       anchor_date = now() - interval '1 hour'
                where org_id = :o
                """).param("o", org).update();
    }

    private long invoiceCount(String org) {
        return jdbc.sql("select count(*) from invoice where org_id = :o").param("o", org).query(Long.class).single();
    }

    private String column(String org, String column) {
        return jdbc.sql("select " + column + "::text from subscription where org_id = :o").param("o", org)
                .query(String.class).optional().orElse(null);
    }
}
