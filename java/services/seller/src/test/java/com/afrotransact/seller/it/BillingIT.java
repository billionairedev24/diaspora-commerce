package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import com.afrotransact.seller.application.BillingService;
import com.afrotransact.seller.application.SellerEvents;
import com.afrotransact.seller.domain.Cadence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** S103–S104: plan catalogue, upgrades vs downgrades, payment methods, invoices and dunning via billing webhooks. */
class BillingIT extends SellerIntegrationTest {

    private static final String BILLING = "/v1/webhooks/stripe-billing";

    private BillingService billing;

    @BeforeEach
    void wire(@Autowired BillingService billingService) {
        this.billing = billingService;
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
        assertThat(get("/v1/plans").body().size()).isEqualTo(6);
        assertThat(get("/v1/plans?kind=SELLER").body().get(0).at("/prices/0/perMonthLabel").asString())
                .isEqualTo("Free");
    }

    @Test
    void upgradesProrateNowDowngradesAndCadenceChangesWaitForRenewal() {
        String owner = unique("bill");
        String org = approvedSeller(owner, "seller_growth", Cadence.MONTHLY);
        String sub = column(org, "stripe_subscription_id");
        String customer = column(org, "stripe_customer_id");
        assertThat(column(org, "status")).isEqualTo("TRIALING");
        long periodEnd = System.currentTimeMillis() / 1000 + 20 * 86400;
        String active = StripeEvents.event(unique("evt"), "customer.subscription.updated", periodEnd - 100, null,
                StripeEvents.subscription(sub, customer, "active", "seller_growth_monthly", periodEnd));
        assertThat(webhook(BILLING, active, sign(active, BILLING_SECRET)).text("/outcome")).isEqualTo("applied");
        assertThat(column(org, "status")).isEqualTo("ACTIVE");
        assertThat(webhook(BILLING, active, sign(active, CONNECT_SECRET)).status()).as("connect secret on billing")
                .isEqualTo(400);

        TestActors.as(TestActors.member(unique("fin"), org, "seller.finance"));
        assertThat(get("/v1/orgs/" + org + "/billing").status()).isEqualTo(200);
        assertThat(post("/v1/orgs/" + org + "/billing/plan", Map.of("planCode", "seller_pro")).status())
                .isEqualTo(403);

        TestActors.as(TestActors.applicant(owner));
        Response overview = get("/v1/orgs/" + org + "/billing");
        assertThat(overview.text("/planName")).isEqualTo("Seller Growth");
        assertThat(overview.text("/nextChargeLabel")).startsWith("Next charge $39 on ").endsWith("to Visa ••4242");

        // S103: the owner sees what an upgrade costs now (Stripe's proration preview) before confirming it; a
        // downgrade or cadence change would only apply at renewal. Nothing changes on the subscription.
        int beforePreview = StripeStub.requests(STRIPE, "POST", "/v1/subscriptions/" + sub).size();
        Response preview = get("/v1/orgs/" + org + "/billing/plan/preview?planCode=seller_pro&cadence=MONTHLY");
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.text("/change")).isEqualTo("IMMEDIATE_UPGRADE");
        assertThat(preview.body().path("amountDueNowCents").asLong()).isEqualTo(1734);
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/invoices/create_preview")).anySatisfy(b -> assertThat(b)
                .contains("subscription=" + sub, "subscription_details[proration_behavior]=always_invoice"));
        Response later = get("/v1/orgs/" + org + "/billing/plan/preview?planCode=seller_growth&cadence=ANNUAL");
        assertThat(later.text("/change")).isEqualTo("AT_RENEWAL");
        assertThat(later.body().path("amountDueNowCents").asLong()).isZero();
        assertThat(StripeStub.requests(STRIPE, "POST", "/v1/subscriptions/" + sub)).hasSize(beforePreview);

        Response upgrade = post("/v1/orgs/" + org + "/billing/plan", Map.of("planCode", "seller_pro",
                "cadence", "MONTHLY"));
        assertThat(upgrade.status()).isEqualTo(200);
        assertThat(upgrade.text("/applied")).isEqualTo("IMMEDIATE_UPGRADE");
        assertThat(upgrade.text("/billing/planCode")).isEqualTo("seller_pro");
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/subscriptions/" + sub)).anySatisfy(b -> assertThat(b)
                .contains("proration_behavior=always_invoice", "metadata[plan_lookup_key]=seller_pro_monthly",
                        "items[0][id]=si_"));
        assertThat(lastEvent(SellerEvents.SUBSCRIPTION_CHANGED, org).path("change").asString()).isEqualTo("UPGRADED");
        int stripeUpdates = StripeStub.requests(STRIPE, "POST", "/v1/subscriptions/" + sub).size();

        Response downgrade = post("/v1/orgs/" + org + "/billing/plan", Map.of("planCode", "seller_growth",
                "cadence", "MONTHLY"));
        assertThat(downgrade.text("/applied")).isEqualTo("AT_RENEWAL");
        assertThat(downgrade.text("/billing/planCode")).isEqualTo("seller_pro");
        assertThat(downgrade.text("/billing/pendingChange/planCode")).isEqualTo("seller_growth");
        assertThat(downgrade.text("/billing/nextChargeLabel")).startsWith("Next charge $39 on ");
        assertThat(StripeStub.requests(STRIPE, "POST", "/v1/subscriptions/" + sub)).hasSize(stripeUpdates);
        assertThat(lastEvent(SellerEvents.SUBSCRIPTION_CHANGED, org).path("change").asString())
                .isEqualTo("CHANGE_SCHEDULED");

        Response cadence = post("/v1/orgs/" + org + "/billing/plan", Map.of("planCode", "seller_pro",
                "cadence", "ANNUAL"));
        assertThat(cadence.text("/applied")).isEqualTo("AT_RENEWAL");
        assertThat(cadence.text("/billing/pendingChange/cadence")).isEqualTo("ANNUAL");
        Response keep = post("/v1/orgs/" + org + "/billing/plan", Map.of("planCode", "seller_pro",
                "cadence", "MONTHLY"));
        assertThat(keep.text("/applied")).isEqualTo("NO_CHANGE");
        assertThat(keep.body().path("billing").path("pendingChange").isNull()).isTrue();

        post("/v1/orgs/" + org + "/billing/plan", Map.of("planCode", "seller_growth", "cadence", "MONTHLY"));
        jdbc.sql("update subscription set pending_effective_at = :t where org_id = :o")
                .param("t", Timestamp.from(Instant.now().minusSeconds(60))).param("o", org).update();
        assertThat(billing.applyDueChanges()).isGreaterThanOrEqualTo(1);
        assertThat(column(org, "plan_code")).isEqualTo("seller_growth");
        assertThat(column(org, "pending_plan_code")).isNull();
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/subscriptions/" + sub)).anySatisfy(b -> assertThat(b)
                .contains("proration_behavior=none", "metadata[plan_lookup_key]=seller_growth_monthly"));
        assertThat(lastEvent(SellerEvents.SUBSCRIPTION_CHANGED, org).path("change").asString())
                .isEqualTo("CHANGE_APPLIED");

        Response cancel = post("/v1/orgs/" + org + "/billing/cancel", Map.of());
        assertThat(cancel.body().path("cancelAtPeriodEnd").asBoolean()).isTrue();
        assertThat(cancel.text("/nextChargeLabel")).startsWith("Ends ");
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/subscriptions/" + sub)).anySatisfy(b -> assertThat(b)
                .contains("cancel_at_period_end=true"));
        assertThat(post("/v1/orgs/" + org + "/billing/resume", Map.of()).body().path("cancelAtPeriodEnd")
                .asBoolean()).isFalse();
    }

    @Test
    void paymentMethodsInvoicesAndDunning() {
        String owner = unique("pm");
        String org = approvedSeller(owner, "seller_pro", Cadence.QUARTERLY);
        String customer = column(org, "stripe_customer_id");
        TestActors.as(TestActors.applicant(owner));

        Response methods = get("/v1/orgs/" + org + "/billing/payment-methods");
        assertThat(methods.body().size()).isEqualTo(1);
        String original = methods.body().get(0).path("id").asString();
        assertThat(methods.body().get(0).path("isDefault").asBoolean()).isTrue();
        assertThat(methods.body().get(0).path("label").asString()).isEqualTo("Visa ••4242");

        Response expired = post("/v1/orgs/" + org + "/billing/payment-methods", Map.of("stripePaymentMethodId",
                "pm_old", "kind", "CARD", "brand", "visa", "last4", "1111", "expMonth", 1, "expYear", 2020));
        assertThat(expired.status()).isEqualTo(422);
        Response added = post("/v1/orgs/" + org + "/billing/payment-methods", Map.of("stripePaymentMethodId",
                "pm_card_mastercard", "kind", "CARD", "brand", "mastercard", "last4", "4444", "expMonth", 8,
                "expYear", 2031));
        assertThat(added.status()).isEqualTo(201);
        assertThat(added.body().path("isDefault").asBoolean()).isFalse();
        Response bank = post("/v1/orgs/" + org + "/billing/payment-methods", Map.of("stripePaymentMethodId",
                "pm_usbank", "kind", "US_BANK_ACCOUNT", "bankName", "Chase", "accountType", "checking",
                "last4", "6789"));
        assertThat(bank.body().path("label").asString()).isEqualTo("Chase checking ••6789");
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/payment_methods/pm_card_mastercard/attach"))
                .anySatisfy(b -> assertThat(b).contains("customer=" + customer));

        Response refuse = delete("/v1/orgs/" + org + "/billing/payment-methods/" + original);
        assertThat(refuse.status()).isEqualTo(409);
        assertThat(refuse.text("/code")).isEqualTo("seller.default-payment-method");
        String mastercard = added.body().path("id").asString();
        Response switched = post("/v1/orgs/" + org + "/billing/payment-methods/" + mastercard + "/default", Map.of());
        assertThat(switched.body().get(0).path("id").asString()).isEqualTo(mastercard);
        assertThat(StripeStub.bodies(STRIPE, "POST", "/v1/customers/" + customer)).anySatisfy(b -> assertThat(b)
                .contains("invoice_settings[default_payment_method]=pm_card_mastercard"));
        Response removed = delete("/v1/orgs/" + org + "/billing/payment-methods/" + original);
        assertThat(removed.status()).isEqualTo(200);
        assertThat(removed.body().size()).isEqualTo(2);
        assertThat(StripeStub.requests(STRIPE, "POST", "/v1/payment_methods/pm_card_" + owner.replace("-", "")
                + "/detach")).hasSize(1);

        TestActors.as(TestActors.member(unique("mkt"), org, "seller.marketing"));
        assertThat(get("/v1/orgs/" + org + "/billing/invoices").status()).isEqualTo(403);

        long next = System.currentTimeMillis() / 1000 + 3 * 86400;
        String failedId = "in_" + unique("f").replace("-", "");
        String failed = StripeEvents.event(unique("evt"), "invoice.payment_failed", next, null,
                StripeEvents.invoice(failedId, customer, "open", 36765, 1, next));
        assertThat(webhook(BILLING, failed, sign(failed, BILLING_SECRET)).text("/outcome")).isEqualTo("applied");
        assertThat(column(org, "status")).isEqualTo("PAST_DUE");
        assertThat(column(org, "dunning")).isEqualTo("RETRYING");
        assertThat(lastEvent(SellerEvents.SUBSCRIPTION_CHANGED, org).path("change").asString())
                .isEqualTo("PAYMENT_FAILED");

        String paid = StripeEvents.event(unique("evt"), "invoice.paid", next + 10, null,
                StripeEvents.invoice(failedId, customer, "paid", 36765, 2, null));
        webhook(BILLING, paid, sign(paid, BILLING_SECRET));
        assertThat(column(org, "status")).isEqualTo("ACTIVE");
        assertThat(column(org, "dunning")).isEqualTo("NONE");

        TestActors.as(TestActors.applicant(owner));
        Response invoices = get("/v1/orgs/" + org + "/billing/invoices");
        assertThat(invoices.body().size()).isEqualTo(1);
        assertThat(invoices.body().get(0).path("status").asString()).isEqualTo("paid");
        assertThat(invoices.body().get(0).path("amountPaidCents").asLong()).isEqualTo(36765);
        assertThat(invoices.body().get(0).path("invoicePdf").asString()).endsWith("/pdf");
    }

    private String column(String org, String column) {
        return jdbc.sql("select " + column + "::text from subscription where org_id = :o").param("o", org)
                .query(String.class).optional().orElse(null);
    }
}
