package com.afrotransact.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.port.PaymentRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seller plan fees share the platform webhook: their events are handed to seller (payments.plan_fee.stripe_event) and
 * never reach the order handlers; order payments are left alone.
 */
class PlanFeeEventForwarderTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final StripeGateway stripe = mock(StripeGateway.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final PlanFeeEventForwarder forwarder = new PlanFeeEventForwarder(payments, stripe, events);
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aPlanFeePaymentIntentIsHandedToSellerWithItsInvoice() {
        JsonNode pi = json.readTree("{\"id\":\"pi_1\",\"object\":\"payment_intent\",\"status\":\"processing\","
                + "\"amount\":3900,\"currency\":\"usd\",\"payment_method\":\"pm_ach\","
                + "\"metadata\":{\"purpose\":\"plan_fee\",\"invoice_id\":\"inv_9\"}}");
        assertThat(forwarder.forward("evt_1", "payment_intent.processing", 100, pi)).isTrue();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(PaymentsEvents.PLAN_FEE_STRIPE_EVENT), eq(PaymentsEvents.AGGREGATE_PAYMENT),
                eq("inv_9"), payload.capture());
        PaymentsEventPayloads.PlanFeeStripeEvent e = (PaymentsEventPayloads.PlanFeeStripeEvent) payload.getValue();
        assertThat(e.stripeEventId()).isEqualTo("evt_1");
        assertThat(e.paymentIntentId()).isEqualTo("pi_1");
        assertThat(e.status()).isEqualTo("processing");
        assertThat(e.amountMinor()).isEqualTo(3900);
        assertThat(e.purpose()).isEqualTo("plan_fee");
    }

    @Test
    void aFailedPlanFeeCarriesTheFailure() {
        JsonNode pi = json.readTree("{\"id\":\"pi_2\",\"object\":\"payment_intent\",\"status\":\"requires_payment_method\","
                + "\"metadata\":{\"purpose\":\"plan_fee\",\"invoice_id\":\"inv_9\"},\"last_payment_error\":"
                + "{\"code\":\"account_closed\",\"message\":\"The bank account is closed.\"}}");
        assertThat(forwarder.forward("evt_2", "payment_intent.payment_failed", 100, pi)).isTrue();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(anyString(), anyString(), anyString(), payload.capture());
        PaymentsEventPayloads.PlanFeeStripeEvent e = (PaymentsEventPayloads.PlanFeeStripeEvent) payload.getValue();
        assertThat(e.failureCode()).isEqualTo("account_closed");
        assertThat(e.failureMessage()).isEqualTo("The bank account is closed.");
    }

    @Test
    void anOrderPaymentIntentIsNotForwarded() {
        JsonNode pi = json.readTree("{\"id\":\"pi_o\",\"object\":\"payment_intent\",\"status\":\"succeeded\","
                + "\"metadata\":{\"order_id\":\"ord_1\"}}");
        assertThat(forwarder.forward("evt_3", "payment_intent.succeeded", 100, pi)).isFalse();
        verify(events, never()).publish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void aDisputeIsMatchedThroughItsPaymentIntentWhenNoOrderPaymentHasIt() {
        JsonNode dispute = json.readTree("{\"id\":\"dp_1\",\"object\":\"dispute\",\"charge\":\"ch_1\","
                + "\"payment_intent\":\"pi_plan\",\"amount\":3900,\"status\":\"needs_response\",\"reason\":\"fraudulent\","
                + "\"metadata\":{}}");
        when(payments.findByIntentId("pi_plan")).thenReturn(Optional.empty());
        when(stripe.intentMetadata("pi_plan")).thenReturn(Map.of("purpose", "plan_fee", "invoice_id", "inv_9"));
        assertThat(forwarder.forward("evt_4", "charge.dispute.created", 100, dispute)).isTrue();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(anyString(), anyString(), anyString(), payload.capture());
        PaymentsEventPayloads.PlanFeeStripeEvent e = (PaymentsEventPayloads.PlanFeeStripeEvent) payload.getValue();
        assertThat(e.disputeId()).isEqualTo("dp_1");
        assertThat(e.disputeStatus()).isEqualTo("needs_response");
        assertThat(e.chargeId()).isEqualTo("ch_1");
        assertThat(e.paymentIntentId()).isEqualTo("pi_plan");
    }

    @Test
    void aDisputeOfAnOrderPaymentStaysWithPayments() {
        JsonNode dispute = json.readTree("{\"id\":\"dp_2\",\"object\":\"dispute\",\"charge\":\"ch_2\","
                + "\"payment_intent\":\"pi_order\",\"metadata\":{}}");
        when(payments.findByIntentId("pi_order")).thenReturn(Optional.of(mock(Payment.class)));
        assertThat(forwarder.forward("evt_5", "charge.dispute.created", 100, dispute)).isFalse();
        verify(stripe, never()).intentMetadata(anyString());
    }

    @Test
    void aRefundedPlanFeeChargeCarriesTheCumulativeRefund() {
        JsonNode charge = json.readTree("{\"id\":\"ch_3\",\"object\":\"charge\",\"payment_intent\":\"pi_3\","
                + "\"amount\":3900,\"amount_refunded\":1000,\"status\":\"succeeded\","
                + "\"metadata\":{\"purpose\":\"plan_fee\",\"invoice_id\":\"inv_3\"}}");
        assertThat(forwarder.forward("evt_6", "charge.refunded", 100, charge)).isTrue();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(anyString(), anyString(), eq("inv_3"), payload.capture());
        PaymentsEventPayloads.PlanFeeStripeEvent e = (PaymentsEventPayloads.PlanFeeStripeEvent) payload.getValue();
        assertThat(e.amountRefundedMinor()).isEqualTo(1000);
        assertThat(e.chargeId()).isEqualTo("ch_3");
    }

    @Test
    void aSellerBillingSetupIntentIsForwardedWithItsMandateAndOthersAreNot() {
        JsonNode si = json.readTree("{\"id\":\"seti_1\",\"object\":\"setup_intent\",\"status\":\"succeeded\","
                + "\"payment_method\":\"pm_ach\",\"mandate\":\"mandate_1\",\"metadata\":{\"purpose\":\"seller_billing\"}}");
        assertThat(forwarder.forward("evt_7", "setup_intent.succeeded", 100, si)).isTrue();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(anyString(), anyString(), eq("seti_1"), payload.capture());
        PaymentsEventPayloads.PlanFeeStripeEvent e = (PaymentsEventPayloads.PlanFeeStripeEvent) payload.getValue();
        assertThat(e.mandateId()).isEqualTo("mandate_1");
        assertThat(e.paymentMethodId()).isEqualTo("pm_ach");

        JsonNode shopper = json.readTree("{\"id\":\"seti_2\",\"object\":\"setup_intent\",\"metadata\":{}}");
        assertThat(forwarder.forward("evt_8", "setup_intent.succeeded", 100, shopper)).isFalse();
        assertThat(forwarder.forward("evt_9", "payout.paid", 100, si)).isFalse();
    }
}
