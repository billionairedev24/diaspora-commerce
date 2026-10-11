package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.afrotransact.payments.application.PaymentsEvents;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** S052: signed, idempotent Stripe webhook inbox processed asynchronously. */
class WebhookInboxIT extends PaymentsIntegrationTest {

    private JsonNode pendingPayment(String order) {
        STRIPE.createIntent(order, "pi_" + order, 2500);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        JsonNode p = ok(send("POST", "/v1/payments", Map.of("orderId", order, "amountMinor", 2500,
                "currency", "USD", "lines", List.of(line("L1", "org_w", "Books", "SELF", 1, 2500, 0, 0))),
                "c-" + order), 201);
        TestSupport.reset();
        return p;
    }

    private String status(String order) {
        return jdbc.sql("select status from payment where order_id = :o").param("o", order).query(String.class)
                .single();
    }

    @Test
    void validSignatureIsStoredOnceAndProcessed() {
        String order = id("ORD");
        pendingPayment(order);
        String evt = "evt_" + order;
        String payload = event(evt, "payment_intent.amount_capturable_updated",
                StripeStubs.intent("pi_" + order, 2500, "requires_capture", null, 0));

        JsonNode ack = webhook(payload);
        assertThat(ack.path("received").asBoolean()).isTrue();
        assertThat(ack.path("duplicate").asBoolean()).isFalse();
        assertThat(ack.path("handled").asBoolean()).isTrue();
        assertThat(status(order)).isEqualTo("AUTHORIZED");
        assertThat(outbox(PaymentsEvents.INTENT_SUCCEEDED, order)).isEqualTo(1);
        assertThat(jdbc.sql("select status from stripe_event where id = :id").param("id", evt).query(String.class)
                .single()).isEqualTo("PROCESSED");

        JsonNode again = webhook(payload);
        assertThat(again.path("duplicate").asBoolean()).isTrue();
        assertThat(outbox(PaymentsEvents.INTENT_SUCCEEDED, order)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from stripe_event where id = :id").param("id", evt).query(Long.class)
                .single()).isEqualTo(1);
    }

    @Test
    void invalidOrMissingSignaturesAreRejectedAndNothingIsStored() {
        String evt = id("evt_bad");
        String payload = event(evt, "payment_intent.succeeded", StripeStubs.intent("pi_x", 1, "succeeded", null, 1));
        long now = TestSupport.CLOCK.instant().getEpochSecond();
        problem(deliver(payload, signature(payload, now, "whsec_wrong_secret")), 400, "payments.invalid-signature");
        problem(deliver(payload, null), 400, "payments.invalid-signature");
        problem(deliver(payload.replace("succeeded", "canceled"), signature(payload, now, WEBHOOK_SECRET)), 400,
                "payments.invalid-signature");
        problem(deliver(payload, signature(payload, now - 3600, WEBHOOK_SECRET)), 400, "payments.invalid-signature");
        assertThat(jdbc.sql("select count(*) from stripe_event where id = :id").param("id", evt).query(Long.class)
                .single()).isZero();
    }

    @Test
    void unknownTypesAreStoredAndAcknowledged() {
        String evt = id("evt_unknown");
        JsonNode ack = webhook(event(evt, "customer.tax_id.created", "{\"id\":\"txi_1\"}"));
        assertThat(ack.path("handled").asBoolean()).isFalse();
        assertThat(jdbc.sql("select status from stripe_event where id = :id").param("id", evt).query(String.class)
                .single()).isEqualTo("IGNORED");
    }

    @Test
    void threeDSecureAndDeclinesUpdateThePayment() {
        String order = id("ORD");
        pendingPayment(order);
        String pi = "pi_" + order;
        webhook(event(id("evt"), "payment_intent.requires_action",
                StripeStubs.intent(pi, 2500, "requires_action", null, 0)));
        assertThat(status(order)).isEqualTo("REQUIRES_ACTION");

        String declined = "{\"id\":\"" + pi + "\",\"object\":\"payment_intent\",\"status\":\"requires_payment_method\","
                + "\"last_payment_error\":{\"message\":\"Your card was declined.\",\"decline_code\":"
                + "\"generic_decline\"}}";
        webhook(event(id("evt"), "payment_intent.payment_failed", declined));
        assertThat(status(order)).isEqualTo("FAILED");
        assertThat(jdbc.sql("select failure_message from payment where order_id = :o").param("o", order)
                .query(String.class).single()).isEqualTo("Your card was declined. (generic_decline)");
        assertThat(outbox(PaymentsEvents.INTENT_FAILED, order)).isEqualTo(1);

        webhook(event(id("evt"), "payment_intent.amount_capturable_updated",
                StripeStubs.intent(pi, 2500, "requires_capture", null, 0)));
        assertThat(status(order)).isEqualTo("AUTHORIZED");
    }

    @Test
    void failingEventsAreRetriedWithBackoff() {
        String evt = id("evt_early");
        webhook(event(evt, "charge.dispute.closed", "{\"id\":\"dp_never_opened_" + evt + "\",\"status\":\"won\"}"));
        JsonNode row = JSON.readTree(jdbc.sql("select row_to_json(e)::text from stripe_event e where id = :id")
                .param("id", evt).query(String.class).single());
        assertThat(row.path("status").asString()).isEqualTo("RECEIVED");
        assertThat(row.path("attempts").asInt()).isEqualTo(1);
        assertThat(row.path("last_error").asString()).contains("will retry");
    }

    /** Webhooks are not ordered: a payment_failed sent before the successful retry may arrive after it. */
    @Test
    void aStalePaymentFailedAfterAuthorisationLeavesThePaymentAuthorised() {
        String order = id("STALE");
        String pi = "pi_" + order;
        pendingPayment(order);
        webhook(event("evt_ok_" + order, "payment_intent.amount_capturable_updated",
                StripeStubs.intent(pi, 2500, "requires_capture", null, 0)));
        assertThat(status(order)).isEqualTo("AUTHORIZED");

        STRIPE.retrieveIntent(pi, 2500, "requires_capture");
        String declined = StripeStubs.intent(pi, 2500, "requires_payment_method", null, 0).replace("}",
                ",\"last_payment_error\":{\"code\":\"card_declined\",\"decline_code\":\"insufficient_funds\","
                        + "\"message\":\"Your card has insufficient funds.\"}}");
        webhook(event("evt_old_fail_" + order, "payment_intent.payment_failed", declined));
        webhook(event("evt_old_action_" + order, "payment_intent.requires_action",
                StripeStubs.intent(pi, 2500, "requires_action", null, 0)));

        assertThat(status(order)).isEqualTo("AUTHORIZED");
        assertThat(outbox(PaymentsEvents.INTENT_FAILED, order)).isZero();
        assertThat(outbox(PaymentsEvents.INTENT_SUCCEEDED, order)).isEqualTo(1);
    }

    /** The order expired (or was cancelled) while the customer was paying: the late authorisation is released. */
    @Test
    void anAuthorisationOfACancelledPaymentIsCancelledAtStripe() {
        String order = id("LATECXL");
        String pi = "pi_" + order;
        JsonNode p = pendingPayment(order);
        STRIPE.cancel(pi, 2500);
        TestSupport.as(TestSupport.ORDERS_SERVICE);
        ok(send("POST", "/v1/payments/" + p.path("id").asString() + "/cancel", Map.of("reason", "expired"),
                "cancel-" + order));
        TestSupport.reset();
        assertThat(status(order)).isEqualTo("CANCELED");

        STRIPE.retrieveIntent(pi, 2500, "requires_capture");
        webhook(event("evt_late_" + order, "payment_intent.amount_capturable_updated",
                StripeStubs.intent(pi, 2500, "requires_capture", null, 0)));

        assertThat(status(order)).isEqualTo("CANCELED");
        WIREMOCK.verify(1, WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/payment_intents/" + pi + "/cancel"))
                .withHeader("Idempotency-Key", WireMock.equalTo(order + ":cancel-late-authorization")));
        assertThat(outbox(PaymentsEvents.INTENT_SUCCEEDED, order)).isZero();
    }
}
