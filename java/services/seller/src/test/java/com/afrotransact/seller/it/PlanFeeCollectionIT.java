package com.afrotransact.seller.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.seller.application.SellerPermissions;
import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.domain.Offering;
import com.afrotransact.seller.domain.PlanFeeCollection;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * S298: sellers choose how they pay their plan (from their sales first, or always by card); a renewal invoice of an
 * EARNINGS_FIRST seller is withheld from its earnings at payments and credited to the Stripe customer balance before
 * it finalises (Idempotency-Keys per invoice), released (and the credit taken back) when the invoice is voided, and
 * redelivered by Stripe (503) while payments is unavailable. Payments is stubbed on the Stripe WireMock.
 */
class PlanFeeCollectionIT extends SellerIntegrationTest {

    private static final String BILLING_HOOK = "/v1/webhooks/stripe-billing";

    private static String invoiceId() {
        return "in_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
    }

    private String customer(String org) {
        return jdbc.sql("select stripe_customer_id from subscription where org_id = :o").param("o", org)
                .query(String.class).single();
    }

    private void paymentsWithholds(String org, String invoice, String status, long withheld) {
        STRIPE.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/orgs/" + org + "/plan-fee-withholdings"))
                .withRequestBody(WireMock.containing(invoice))
                .willReturn(WireMock.aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"wh_" + invoice + "\",\"status\":\"" + status + "\",\"withheldMinor\":"
                                + withheld + ",\"reason\":" + (withheld < 3900 ? "\"Only part was available\""
                                : "null") + "}")));
    }

    private Response invoiceEvent(String eventId, String type, String invoice, String customer, String status) {
        long now = System.currentTimeMillis() / 1000;
        String payload = StripeEvents.event(eventId, type, now, null, StripeEvents.invoice(invoice, customer, status,
                3900, 0, null));
        return webhook(BILLING_HOOK, payload, sign(payload, BILLING_SECRET));
    }

    private List<LoggedRequest> requests(String method, String path) {
        return StripeStub.requests(STRIPE, method, path);
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
        assertThat(view.text("/mode")).as("new sellers get the platform default").isEqualTo("EARNINGS_FIRST");
        assertThat(view.text("/source")).isEqualTo("DEFAULT");
        assertThat(view.text("/appliesFromInvoiceAt")).isNotNull();
        assertThat(view.text("/appliesFromLabel")).startsWith("Applies from your next invoice, on ");

        Response card = put(path, Map.of("mode", "CARD"));
        assertThat(card.status()).isEqualTo(200);
        assertThat(card.text("/mode")).isEqualTo("CARD");
        assertThat(card.text("/source")).isEqualTo("SELLER");
        assertThat(card.body().path("history").size()).isEqualTo(1);
        assertThat(card.text("/history/0/previous")).isEqualTo("EARNINGS_FIRST");
        assertThat(card.text("/history/0/appliesFrom")).isEqualTo(view.text("/appliesFromInvoiceAt"));
        assertThat(put(path, Map.of("mode", "CARD")).body().path("history").size()).as("no change, no entry")
                .isEqualTo(1);
        assertThat(put(path, Map.of("mode", "SOMETIMES")).status()).isEqualTo(422);
        assertThat(get("/v1/orgs/" + org + "/billing").text("/planFeeCollection")).isEqualTo("CARD");

        // A CARD seller's renewal is charged to the card: payments is never asked.
        String invoice = invoiceId();
        Response created = invoiceEvent(unique("evt"), "invoice.created", invoice, customer(org), "draft");
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.text("/outcome")).isEqualTo("applied");
        assertThat(requests("POST", "/v1/orgs/" + org + "/plan-fee-withholdings")).isEmpty();

        TestActors.as(TestActors.member(unique("fin"), org, "seller.finance"));
        assertThat(put(path, Map.of("mode", "EARNINGS_FIRST")).text("/mode")).isEqualTo("EARNINGS_FIRST");
        TestActors.as(TestActors.member(unique("cat"), org, "seller.catalog_manager"));
        assertThat(put(path, Map.of("mode", "CARD")).status()).as("owner and finance only").isEqualTo(403);
        assertThat(get(path).status()).isEqualTo(403);
    }

    @Test
    void aRenewalIsPaidFromSalesFirstWithKeysPerInvoiceAndAVoidGivesItBack() {
        String owner = unique("pfe");
        String org = approvedSeller(owner, "seller_growth", Cadence.MONTHLY);
        String customer = customer(org);
        String invoice = invoiceId();
        paymentsWithholds(org, invoice, "WITHHELD", 2500);

        String event = unique("evt");
        Response created = invoiceEvent(event, "invoice.created", invoice, customer, "draft");
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.text("/outcome")).isEqualTo("earnings-requested");

        List<LoggedRequest> withheld = requests("POST", "/v1/orgs/" + org + "/plan-fee-withholdings");
        assertThat(withheld).hasSize(1);
        assertThat(withheld.getFirst().getHeader("Idempotency-Key")).isEqualTo("plan-fee-" + invoice);
        JsonNode body = JSON.readTree(withheld.getFirst().getBodyAsString());
        assertThat(body.path("invoiceId").asString()).isEqualTo(invoice);
        assertThat(body.path("amountMinor").asLong()).isEqualTo(3900);
        List<LoggedRequest> credits = requests("POST", "/v1/customers/" + customer + "/balance_transactions");
        assertThat(credits).hasSize(1);
        assertThat(credits.getFirst().getHeader("Idempotency-Key")).isEqualTo("earnings-" + invoice);
        assertThat(StripeStub.decoded(credits.getFirst())).contains("amount=-2500", "currency=usd",
                "description=Plan fee paid from your sales (invoice INV-" + invoice + ")");

        // A redelivery is a duplicate: nothing is withheld or credited again.
        Response again = invoiceEvent(event, "invoice.created", invoice, customer, "draft");
        assertThat(again.body().path("duplicate").asBoolean()).isTrue();
        assertThat(requests("POST", "/v1/orgs/" + org + "/plan-fee-withholdings")).hasSize(1);
        assertThat(requests("POST", "/v1/customers/" + customer + "/balance_transactions")).hasSize(1);

        TestActors.as(TestActors.applicant(owner));
        JsonNode listed = get("/v1/orgs/" + org + "/billing/invoices").body().valueStream()
                .filter(i -> i.path("id").asString().equals(invoice)).findFirst().orElseThrow();
        assertThat(listed.path("paidFromSalesCents").asLong()).isEqualTo(2500);
        assertThat(listed.path("paidByCardCents").asLong()).as("the card pays the rest").isEqualTo(1400);

        // Voided: the credit is taken back from the customer balance, then the earnings are released.
        STRIPE.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/orgs/" + org + "/plan-fee-withholdings/"
                + invoice + "/release")).willReturn(WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"status\":\"RELEASED\","
                        + "\"releasedMinor\":2500}")));
        Response voided = invoiceEvent(unique("evt"), "invoice.voided", invoice, customer, "void");
        assertThat(voided.status()).isEqualTo(200);
        assertThat(voided.text("/outcome")).isEqualTo("earnings-release-requested");
        List<LoggedRequest> debits = requests("POST", "/v1/customers/" + customer + "/balance_transactions").stream()
                .filter(r -> ("earnings-plan-fee-release-" + invoice).equals(r.getHeader("Idempotency-Key"))).toList();
        assertThat(debits).as("the credit is taken back once").hasSize(1);
        assertThat(StripeStub.decoded(debits.getFirst())).contains("amount=2500");
        List<LoggedRequest> released = requests("POST", "/v1/orgs/" + org + "/plan-fee-withholdings/" + invoice
                + "/release");
        assertThat(released).hasSize(1);
        assertThat(released.getFirst().getHeader("Idempotency-Key")).isEqualTo("plan-fee-release-" + invoice);
        assertThat(jdbc.sql("select status from earnings_collection where stripe_invoice_id = :i")
                .param("i", invoice).query(String.class).single()).isEqualTo("RELEASED");

        TestActors.as(TestActors.staff(unique("viewer"), SellerPermissions.BILLING));
        JsonNode panel = get("/v1/orgs/" + org + "/billing/panel").body();
        JsonNode collection = panel.path("collections").get(0);
        assertThat(collection.path("invoiceId").asString()).isEqualTo(invoice);
        assertThat(collection.path("withheldCents").asLong()).isEqualTo(2500);
        assertThat(collection.path("releasedCents").asLong()).isEqualTo(2500);
        assertThat(panel.path("planFeeCollection").path("mode").asString()).isEqualTo("EARNINGS_FIRST");
    }

    @Test
    void whilePaymentsIsDownStripeIsAskedToRedeliverAndTheRetryCollectsOnce() {
        String owner = unique("pfr");
        String org = approvedSeller(owner, "seller_growth", Cadence.MONTHLY);
        String customer = customer(org);
        String invoice = invoiceId();
        STRIPE.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/orgs/" + org + "/plan-fee-withholdings"))
                .willReturn(WireMock.aResponse().withStatus(503)));
        String event = unique("evt");
        Response first = invoiceEvent(event, "invoice.created", invoice, customer, "draft");
        assertThat(first.status()).as("Stripe keeps the invoice a draft and redelivers").isEqualTo(503);
        assertThat(jdbc.sql("select attempts from earnings_collection where stripe_invoice_id = :i")
                .param("i", invoice).query(Integer.class).single()).isEqualTo(1);
        assertThat(requests("POST", "/v1/customers/" + customer + "/balance_transactions")).isEmpty();

        paymentsWithholds(org, invoice, "WITHHELD", 3900);
        jdbc.sql("update earnings_collection set next_attempt_at = now() where stripe_invoice_id = :i")
                .param("i", invoice).update();
        Response redelivered = invoiceEvent(event, "invoice.created", invoice, customer, "draft");
        assertThat(redelivered.status()).isEqualTo(200);
        List<LoggedRequest> credits = requests("POST", "/v1/customers/" + customer + "/balance_transactions");
        assertThat(credits).hasSize(1);
        assertThat(StripeStub.decoded(credits.getFirst())).contains("amount=-3900");
        assertThat(requests("POST", "/v1/orgs/" + org + "/plan-fee-withholdings"))
                .allSatisfy(r -> assertThat(r.getHeader("Idempotency-Key")).isEqualTo("plan-fee-" + invoice));

        // Finalised before the credit could be posted: whatever payments holds is released, the card pays. (The first
        // invoice used up the credit when it finalised.)
        jdbc.sql("update subscription set credit_balance_cents = 0 where org_id = :o").param("o", org).update();
        String late = invoiceId();
        paymentsWithholds(org, late, "WITHHELD", 3900);
        STRIPE.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/invoices/" + late)).atPriority(1)
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + late + "\",\"object\":\"invoice\",\"status\":\"open\"}")));
        assertThat(invoiceEvent(unique("evt"), "invoice.created", late, customer, "draft").status()).isEqualTo(200);
        assertThat(jdbc.sql("select status from earnings_collection where stripe_invoice_id = :i")
                .param("i", late).query(String.class).single()).isEqualTo("MISSED");
        assertThat(requests("POST", "/v1/orgs/" + org + "/plan-fee-withholdings/" + late + "/release")).hasSize(1);
    }

    @Test
    void staffSetThePlatformDefaultAndOverrideOneSellerWithAReason() {
        TestActors.as(TestActors.staff(unique("auditor"), SellerPermissions.BILLING));
        Response settings = get("/v1/billing/settings");
        assertThat(settings.status()).isEqualTo(200);
        assertThat(settings.body().path("earningsCollectionEnabled").asBoolean()).isTrue();
        assertThat(put("/v1/billing/settings", Map.of("planFeeCollectionDefault", "CARD")).status()).isEqualTo(403);

        TestActors.as(TestActors.staff(unique("plans"), SellerPermissions.PLANS_MANAGE));
        try {
            Response changed = put("/v1/billing/settings", Map.of("planFeeCollectionDefault", "CARD"));
            assertThat(changed.text("/planFeeCollectionDefault")).isEqualTo("CARD");
            String byDefault = approvedSeller(unique("pfd"), "seller_growth", Cadence.MONTHLY);
            String chosen = approvedSeller(unique("pfo"), "seller_growth", Cadence.MONTHLY, Offering.PRODUCTS, null,
                    PlanFeeCollection.EARNINGS_FIRST);
            assertThat(planFeeCollection(byDefault)).isEqualTo("CARD:DEFAULT");
            assertThat(planFeeCollection(chosen)).as("the Plan step's choice wins").isEqualTo("EARNINGS_FIRST:"
                    + "ONBOARDING");

            String path = "/v1/orgs/" + byDefault + "/billing/plan-fee-collection/override";
            TestActors.as(TestActors.staff(unique("viewer"), SellerPermissions.BILLING));
            assertThat(put(path, Map.of("mode", "EARNINGS_FIRST", "reasonCode", "GOODWILL")).status())
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
            put("/v1/billing/settings", Map.of("planFeeCollectionDefault", "EARNINGS_FIRST"));
        }
    }

    private String planFeeCollection(String org) {
        return jdbc.sql("select plan_fee_collection || ':' || plan_fee_collection_source from subscription "
                + "where org_id = :o").param("o", org).query(String.class).single();
    }
}
