package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.afrotransact.seller.application.BillingService;
import com.afrotransact.seller.application.InvoiceCollectionService;
import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.Offering;
import com.afrotransact.seller.domain.PlanFeeCollection;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * S298: sellers choose how they pay their plan. Platform credit and coupons always come first; the seller's earnings
 * are only asked for when the seller chose "from my sales" (payments withholds up to what is left, possibly less);
 * the saved card pays the rest. Payments is stubbed on the Stripe WireMock.
 */
class PlanFeeCollectionIT extends SellerIntegrationTest {

    private BillingService billing;
    private InvoiceCollectionService collection;

    @BeforeEach
    void wire(@Autowired BillingService billingService, @Autowired InvoiceCollectionService collectionService) {
        this.billing = billingService;
        this.collection = collectionService;
    }

    private void paymentsWithholds(String org, String status, long withheld, long asked) {
        STRIPE.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/orgs/" + org + "/plan-fee-withholdings"))
                .willReturn(WireMock.aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"wh_" + org + "\",\"status\":\"" + status + "\",\"withheldMinor\":"
                                + withheld + ",\"reason\":" + (withheld < asked ? "\"Only part was available\""
                                : "null") + "}")));
    }

    private List<LoggedRequest> requests(String method, String path) {
        return StripeStub.requests(STRIPE, method, path);
    }

    private void endPeriodNow(String org) {
        jdbc.sql("update subscription set trial_ends_at = null, current_period_end = now() - interval '1 hour', "
                + "anchor_date = now() - interval '1 hour' where org_id = :o").param("o", org).update();
    }

    private String invoiceOf(String org) {
        return jdbc.sql("select stripe_invoice_id from invoice where org_id = :o order by created_at desc limit 1")
                .param("o", org).query(String.class).single();
    }

    private List<String> payments(String invoice) {
        return jdbc.sql("select source || ':' || status || ':' || amount_cents from invoice_payment where "
                + "invoice_id = :i order by created_at, source").param("i", invoice).query(String.class).list();
    }

    @Test
    void ownersAndFinanceChooseHowThePlanIsPaidFromTheNextInvoice() {
        String owner = unique("pfc");
        String org = approvedSeller(owner, "seller_growth", Cadence.MONTHLY);
        String path = "/v1/orgs/" + org + "/billing/plan-fee-collection";

        TestActors.as(TestActors.applicant(owner));
        Response view = get(path);
        assertThat(view.status()).isEqualTo(200);
        assertThat(view.body().path("available").asBoolean()).isTrue();
        assertThat(view.text("/mode")).as("earnings are used only when the seller chooses them").isEqualTo("CARD");
        assertThat(view.text("/source")).isEqualTo("DEFAULT");
        assertThat(view.text("/appliesFromInvoiceAt")).isNotNull();
        assertThat(view.text("/appliesFromLabel")).startsWith("Applies from your next invoice, on ");

        Response sales = put(path, Map.of("mode", "EARNINGS_FIRST"));
        assertThat(sales.status()).isEqualTo(200);
        assertThat(sales.text("/mode")).isEqualTo("EARNINGS_FIRST");
        assertThat(sales.text("/source")).isEqualTo("SELLER");
        assertThat(sales.body().path("history").size()).isEqualTo(1);
        assertThat(sales.text("/history/0/previous")).isEqualTo("CARD");
        assertThat(sales.text("/history/0/appliesFrom")).isEqualTo(view.text("/appliesFromInvoiceAt"));
        assertThat(put(path, Map.of("mode", "EARNINGS_FIRST")).body().path("history").size())
                .as("no change, no entry").isEqualTo(1);
        assertThat(put(path, Map.of("mode", "SOMETIMES")).status()).isEqualTo(422);
        assertThat(get("/v1/orgs/" + org + "/billing").text("/planFeeCollection")).isEqualTo("EARNINGS_FIRST");

        // Back to the card: the renewal is charged to the card and payments is never asked.
        assertThat(put(path, Map.of("mode", "CARD")).text("/mode")).isEqualTo("CARD");
        endPeriodNow(org);
        billing.renewDueSubscriptions();
        assertThat(payments(invoiceOf(org))).containsExactly("CARD:APPLIED:3900");
        assertThat(requests("POST", "/v1/orgs/" + org + "/plan-fee-withholdings")).isEmpty();

        TestActors.as(TestActors.member(unique("fin"), org, "seller.finance"));
        assertThat(put(path, Map.of("mode", "EARNINGS_FIRST")).text("/mode")).isEqualTo("EARNINGS_FIRST");
        TestActors.as(TestActors.member(unique("cat"), org, "seller.catalog_manager"));
        assertThat(put(path, Map.of("mode", "CARD")).status()).as("owner and finance only").isEqualTo(403);
        assertThat(get(path).status()).isEqualTo(403);
    }

    @Test
    void creditComesFirstThenSalesThenTheCard() {
        String owner = unique("pfe");
        String org = approvedSeller(owner, "seller_growth", Cadence.MONTHLY, Offering.PRODUCTS, null,
                PlanFeeCollection.EARNINGS_FIRST);
        jdbc.sql("update subscription set credit_balance_cents = 1000 where org_id = :o").param("o", org).update();
        paymentsWithholds(org, "WITHHELD", 2500, 2900);

        endPeriodNow(org);
        billing.renewDueSubscriptions();
        String invoice = invoiceOf(org);

        List<LoggedRequest> withheld = requests("POST", "/v1/orgs/" + org + "/plan-fee-withholdings");
        assertThat(withheld).hasSize(1);
        assertThat(withheld.getFirst().getHeader("Idempotency-Key")).isEqualTo("plan-fee-" + invoice);
        JsonNode body = JSON.readTree(withheld.getFirst().getBodyAsString());
        assertThat(body.path("invoiceId").asString()).isEqualTo(invoice);
        assertThat(body.path("amountMinor").asLong()).as("$39 less the $10 credit").isEqualTo(2900);
        assertThat(payments(invoice)).containsExactlyInAnyOrder("CREDIT:APPLIED:1000", "EARNINGS:APPLIED:2500",
                "CARD:APPLIED:400");
        assertThat(requests("POST", "/v1/payment_intents")).anySatisfy(r -> assertThat(StripeStub.decoded(r))
                .contains("amount=400", "metadata[invoice_id]=" + invoice));
        assertThat(jdbc.sql("select credit_balance_cents from subscription where org_id = :o").param("o", org)
                .query(Long.class).single()).as("credit used up").isZero();
        assertThat(jdbc.sql("select status from earnings_collection where stripe_invoice_id = :i")
                .param("i", invoice).query(String.class).single()).isEqualTo("CREDITED");

        // Collecting again settles nothing twice.
        collection.collect(invoice);
        assertThat(requests("POST", "/v1/orgs/" + org + "/plan-fee-withholdings")).hasSize(1);
        assertThat(payments(invoice)).hasSize(3);

        TestActors.as(TestActors.applicant(owner));
        JsonNode listed = get("/v1/orgs/" + org + "/billing/invoices").body().valueStream()
                .filter(i -> i.path("id").asString().equals(invoice)).findFirst().orElseThrow();
        assertThat(listed.path("status").asString()).isEqualTo("paid");
        assertThat(listed.path("paidFromCreditCents").asLong()).isEqualTo(1000);
        assertThat(listed.path("paidFromSalesCents").asLong()).isEqualTo(2500);
        assertThat(listed.path("paidByCardCents").asLong()).as("the card pays the rest").isEqualTo(400);

        TestActors.as(TestActors.staff(unique("viewer"), SellerPermissions.BILLING));
        JsonNode panel = get("/v1/orgs/" + org + "/billing/panel").body();
        JsonNode record = panel.path("collections").get(0);
        assertThat(record.path("invoiceId").asString()).isEqualTo(invoice);
        assertThat(record.path("withheldCents").asLong()).isEqualTo(2500);
        assertThat(panel.path("planFeeCollection").path("mode").asString()).isEqualTo("EARNINGS_FIRST");
    }

    @Test
    void creditThatCoversTheInvoiceNeverTouchesEarningsOrTheCard() {
        String org = approvedSeller(unique("pfc2"), "seller_growth", Cadence.MONTHLY, Offering.PRODUCTS, null,
                PlanFeeCollection.EARNINGS_FIRST);
        jdbc.sql("update subscription set credit_balance_cents = 5000 where org_id = :o").param("o", org).update();
        endPeriodNow(org);
        billing.renewDueSubscriptions();
        String invoice = invoiceOf(org);
        assertThat(payments(invoice)).containsExactly("CREDIT:APPLIED:3900");
        assertThat(requests("POST", "/v1/orgs/" + org + "/plan-fee-withholdings")).isEmpty();
        assertThat(jdbc.sql("select credit_balance_cents from subscription where org_id = :o").param("o", org)
                .query(Long.class).single()).isEqualTo(1100);
    }

    @Test
    void whilePaymentsIsDownTheCardWaitsAndTheRetryCollectsOnce() {
        String org = approvedSeller(unique("pfr"), "seller_growth", Cadence.MONTHLY, Offering.PRODUCTS, null,
                PlanFeeCollection.EARNINGS_FIRST);
        STRIPE.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/orgs/" + org + "/plan-fee-withholdings"))
                .willReturn(WireMock.aResponse().withStatus(503)));
        int intentsBefore = requests("POST", "/v1/payment_intents").size();

        endPeriodNow(org);
        billing.renewDueSubscriptions();
        String invoice = invoiceOf(org);
        assertThat(jdbc.sql("select attempts from earnings_collection where stripe_invoice_id = :i")
                .param("i", invoice).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select status from invoice where stripe_invoice_id = :i").param("i", invoice)
                .query(String.class).single()).as("still open: earnings are retried first").isEqualTo("open");
        assertThat(requests("POST", "/v1/payment_intents")).as("the card is not charged yet").hasSize(intentsBefore);
        assertThat(jdbc.sql("select (next_attempt_at > now()) from invoice where stripe_invoice_id = :i")
                .param("i", invoice).query(Boolean.class).single()).isTrue();

        paymentsWithholds(org, "WITHHELD", 3900, 3900);
        jdbc.sql("update invoice set next_attempt_at = now() - interval '1 minute' where stripe_invoice_id = :i")
                .param("i", invoice).update();
        assertThat(collection.collectDue()).isGreaterThanOrEqualTo(1);
        assertThat(payments(invoice)).containsExactly("EARNINGS:APPLIED:3900");
        assertThat(requests("POST", "/v1/payment_intents")).hasSize(intentsBefore);
        assertThat(requests("POST", "/v1/orgs/" + org + "/plan-fee-withholdings"))
                .allSatisfy(r -> assertThat(r.getHeader("Idempotency-Key")).isEqualTo("plan-fee-" + invoice));
    }

    @Test
    void afterTooManyFailuresTheCardPaysInstead() {
        String org = approvedSeller(unique("pfg"), "seller_growth", Cadence.MONTHLY, Offering.PRODUCTS, null,
                PlanFeeCollection.EARNINGS_FIRST);
        STRIPE.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/orgs/" + org + "/plan-fee-withholdings"))
                .willReturn(WireMock.aResponse().withStatus(503)));
        endPeriodNow(org);
        billing.renewDueSubscriptions();
        String invoice = invoiceOf(org);
        jdbc.sql("update earnings_collection set attempts = 11 where stripe_invoice_id = :i").param("i", invoice)
                .update();
        collection.collect(invoice);
        assertThat(jdbc.sql("select status from earnings_collection where stripe_invoice_id = :i")
                .param("i", invoice).query(String.class).single()).isEqualTo("FAILED");
        assertThat(payments(invoice)).containsExactly("CARD:APPLIED:3900");
    }

    @Test
    void staffSetThePlatformDefaultAndOverrideOneSellerWithAReason() {
        TestActors.as(TestActors.staff(unique("auditor"), SellerPermissions.BILLING));
        Response settings = get("/v1/billing/settings");
        assertThat(settings.status()).isEqualTo(200);
        assertThat(settings.body().path("earningsCollectionEnabled").asBoolean()).isTrue();
        assertThat(put("/v1/billing/settings", Map.of("planFeeCollectionDefault", "EARNINGS_FIRST")).status())
                .isEqualTo(403);
        assertThat(settings.text("/planFeeCollectionDefault")).as("sellers opt in to paying from sales")
                .isEqualTo("CARD");

        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        try {
            Response changed = put("/v1/billing/settings", Map.of("planFeeCollectionDefault", "EARNINGS_FIRST"));
            assertThat(changed.text("/planFeeCollectionDefault")).isEqualTo("EARNINGS_FIRST");
            String byDefault = approvedSeller(unique("pfd"), "seller_growth", Cadence.MONTHLY);
            String chosen = approvedSeller(unique("pfo"), "seller_growth", Cadence.MONTHLY, Offering.PRODUCTS, null,
                    PlanFeeCollection.CARD);
            assertThat(planFeeCollection(byDefault)).isEqualTo("EARNINGS_FIRST:DEFAULT");
            assertThat(planFeeCollection(chosen)).as("the Plan step's choice wins").isEqualTo("CARD:ONBOARDING");

            String path = "/v1/orgs/" + byDefault + "/billing/plan-fee-collection/override";
            TestActors.as(TestActors.staff(unique("viewer"), SellerPermissions.BILLING));
            assertThat(put(path, Map.of("mode", "CARD", "reasonCode", "GOODWILL")).status())
                    .isEqualTo(403);
            TestActors.as(TestActors.staff(unique("finance"), SellerPermissions.BILLING_ADJUST));
            assertThat(put(path, Map.of("mode", "EARNINGS_FIRST")).body().path("fieldErrors").has("reasonCode"))
                    .isTrue();
            assertThat(put(path, Map.of("mode", "EARNINGS_FIRST", "reasonCode", "OTHER")).body()
                    .path("fieldErrors").has("note")).isTrue();
            Response overridden = put(path, Map.of("mode", "EARNINGS_FIRST", "reasonCode", "SELLER_REQUEST",
                    "note", "Asked by phone"));
            assertThat(overridden.status()).isEqualTo(200);
            assertThat(overridden.text("/source")).isEqualTo("STAFF");
            assertThat(overridden.text("/history/0/reasonCode")).isEqualTo("SELLER_REQUEST");
            assertThat(overridden.text("/history/0/note")).isEqualTo("Asked by phone");
            assertThat(overridden.text("/history/0/actorId")).startsWith("finance-");
        } finally {
            TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
            put("/v1/billing/settings", Map.of("planFeeCollectionDefault", "CARD"));
        }
    }

    private String planFeeCollection(String org) {
        return jdbc.sql("select plan_fee_collection || ':' || plan_fee_collection_source from subscription "
                + "where org_id = :o").param("o", org).query(String.class).single();
    }
}
