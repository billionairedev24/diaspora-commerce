package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.afrotransact.payments.application.PaymentsEvents;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * One platform endpoint: a seller plan-fee event (PaymentIntent metadata purpose=plan_fee, card or US bank debit) is
 * stored once, never treated as an order payment, and handed to seller as payments.plan_fee.stripe_event through the
 * outbox (key = the plan invoice id); a redelivery publishes nothing twice.
 */
class PlanFeeWebhookIT extends PaymentsIntegrationTest {

    @Test
    void aPlanFeeEventIsHandedToSellerOnceAndNoOrderPaymentIsTouched() {
        String invoice = id("inv");
        String evt = id("evt_plan");
        String intent = "{\"id\":\"pi_" + invoice + "\",\"object\":\"payment_intent\",\"status\":\"processing\","
                + "\"amount\":3900,\"currency\":\"usd\",\"payment_method\":\"pm_ach\","
                + "\"metadata\":{\"purpose\":\"plan_fee\",\"invoice_id\":\"" + invoice + "\"}}";
        String payload = event(evt, "payment_intent.processing", intent);

        JsonNode ack = webhook(payload);
        assertThat(ack.path("handled").asBoolean()).isTrue();
        assertThat(jdbc.sql("select status from stripe_event where id = :id").param("id", evt).query(String.class)
                .single()).isEqualTo("PROCESSED");
        assertThat(outbox(PaymentsEvents.PLAN_FEE_STRIPE_EVENT, invoice)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from payment where stripe_payment_intent_id = :p")
                .param("p", "pi_" + invoice).query(Long.class).single()).isZero();
        String fields = jdbc.sql("select (payload->>'stripeEventId') || '|' || (payload->>'stripeEventType') || '|' || "
                        + "(payload->>'paymentIntentId') from platform_outbox where type = :t and aggregate_id = :a")
                .param("t", PaymentsEvents.PLAN_FEE_STRIPE_EVENT).param("a", invoice).query(String.class).single();
        assertThat(fields).isEqualTo(evt + "|payment_intent.processing|pi_" + invoice);

        assertThat(webhook(payload).path("duplicate").asBoolean()).isTrue();
        assertThat(outbox(PaymentsEvents.PLAN_FEE_STRIPE_EVENT, invoice)).isEqualTo(1);
    }

    @Test
    void aRefundedPlanFeeChargeIsHandedToSeller() {
        String invoice = id("inv");
        String charge = "{\"id\":\"ch_" + invoice + "\",\"object\":\"charge\",\"payment_intent\":\"pi_" + invoice + "\","
                + "\"amount\":3900,\"amount_refunded\":3900,\"status\":\"succeeded\",\"metadata\":{\"purpose\":\"plan_fee\","
                + "\"invoice_id\":\"" + invoice + "\"}}";
        webhook(event(id("evt_ref"), "charge.refunded", charge));
        assertThat(outbox(PaymentsEvents.PLAN_FEE_STRIPE_EVENT, invoice)).isEqualTo(1);
    }
}
