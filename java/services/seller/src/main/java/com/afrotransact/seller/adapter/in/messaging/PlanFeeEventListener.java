package com.afrotransact.seller.adapter.in.messaging;

import java.nio.charset.StandardCharsets;

import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.afrotransact.seller.application.PlanFeePaymentService;
import com.afrotransact.seller.application.PlanFeeStripeEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seller's side of plan-fee payments: payments receives every Stripe event of a plan fee on the platform webhook and
 * hands it over as {@code payments.plan_fee.stripe_event} (at.payment.events). Applied by {@link PlanFeePaymentService}
 * in one transaction with the inbox claim on the Stripe event id, so a redelivery (Kafka or Stripe) is a no-op. Other
 * event types on the topic are skipped; failures propagate so the container retries.
 */
@Component
class PlanFeeEventListener {

    static final String TYPE = "payments.plan_fee.stripe_event";

    private static final Logger LOG = LoggerFactory.getLogger(PlanFeeEventListener.class);

    private final PlanFeePaymentService planFees;
    private final JsonMapper json;

    PlanFeeEventListener(PlanFeePaymentService planFees, JsonMapper json) {
        this.planFees = planFees;
        this.json = json;
    }

    @KafkaListener(id = "seller-plan-fee-payments", groupId = "${spring.kafka.consumer.group-id:seller-service}",
            topics = "${at.seller.consumers.payments-topic:at.payment.events}",
            autoStartup = "${at.seller.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        handle(rec);
    }

    /** Applies one record; returns the outcome, or null for an event type this listener does not handle. */
    String handle(ConsumerRecord<String, String> rec) {
        Header h = rec.headers().lastHeader("event-type");
        String type = h == null || h.value() == null ? "" : new String(h.value(), StandardCharsets.UTF_8);
        if (!TYPE.equals(type)) {
            return null;
        }
        JsonNode p;
        try {
            p = json.readTree(rec.value());
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (text(p, "stripeEventId") == null || text(p, "stripeEventType") == null) {
            LOG.warn("{} without stripeEventId / stripeEventType skipped (offset {})", type, rec.offset());
            return "ignored";
        }
        return planFees.apply(new PlanFeeStripeEvent(text(p, "stripeEventId"), text(p, "stripeEventType"),
                p.path("created").asLong(0), text(p, "purpose"), text(p, "objectType"), text(p, "paymentIntentId"),
                text(p, "invoiceId"), text(p, "orgId"), text(p, "status"), p.path("amountMinor").asLong(0),
                p.path("amountReceivedMinor").asLong(0), p.path("amountRefundedMinor").asLong(0), text(p, "currency"),
                text(p, "chargeId"), text(p, "failureCode"), text(p, "failureMessage"), text(p, "nextActionType"),
                text(p, "disputeId"), text(p, "disputeStatus"), text(p, "disputeReason"), text(p, "setupIntentId"),
                text(p, "paymentMethodId"), text(p, "mandateId")));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }
}
