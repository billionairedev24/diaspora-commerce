package com.afrotransact.seller.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import com.afrotransact.seller.application.PlanFeePaymentService;
import com.afrotransact.seller.application.PlanFeeStripeEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** payments.plan_fee.stripe_event (at.payment.events) reaches PlanFeePaymentService with every field; others are skipped. */
class PlanFeeEventListenerTest {

    private final PlanFeePaymentService planFees = mock(PlanFeePaymentService.class);
    private final PlanFeeEventListener listener = new PlanFeeEventListener(planFees, JsonMapper.builder().build());

    @Test
    void aPlanFeeEventIsAppliedWithItsFields() {
        when(planFees.apply(any())).thenReturn("settled");
        String body = "{\"stripeEventId\":\"evt_1\",\"stripeEventType\":\"payment_intent.payment_failed\",\"created\":5,"
                + "\"purpose\":\"plan_fee\",\"objectType\":\"payment_intent\",\"paymentIntentId\":\"pi_1\","
                + "\"invoiceId\":\"inv_1\",\"amountMinor\":3900,\"failureCode\":\"account_closed\","
                + "\"failureMessage\":\"Closed\",\"amountRefundedMinor\":0}";
        assertThat(listener.handle(rec("payments.plan_fee.stripe_event", body))).isEqualTo("settled");
        ArgumentCaptor<PlanFeeStripeEvent> e = ArgumentCaptor.forClass(PlanFeeStripeEvent.class);
        verify(planFees).apply(e.capture());
        assertThat(e.getValue().stripeEventId()).isEqualTo("evt_1");
        assertThat(e.getValue().paymentIntentId()).isEqualTo("pi_1");
        assertThat(e.getValue().failureCode()).isEqualTo("account_closed");
        assertThat(e.getValue().created()).isEqualTo(5);
    }

    @Test
    void orderPaymentEventsOnTheSameTopicAreSkipped() {
        assertThat(listener.handle(rec("payments.captured", "{}"))).isNull();
        verify(planFees, never()).apply(any());
    }

    private static ConsumerRecord<String, String> rec(String type, String value) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>("at.payment.events", 0, 0L, "inv_1", value);
        r.headers().add("event-type", type.getBytes(StandardCharsets.UTF_8));
        return r;
    }
}
