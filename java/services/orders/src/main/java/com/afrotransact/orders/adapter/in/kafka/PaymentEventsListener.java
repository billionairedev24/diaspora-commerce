package com.afrotransact.orders.adapter.in.kafka;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.afrotransact.orders.application.PaymentEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** payments.intent.* on at.payment.events → checkout saga signals. */
@Component
class PaymentEventsListener {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentEventsListener.class);

    private final PaymentEventService payments;
    private final JsonMapper json;

    PaymentEventsListener(PaymentEventService payments, JsonMapper json) {
        this.payments = payments;
        this.json = json;
    }

    @KafkaListener(id = "orders-payment-events", topics = "${at.orders.topics.payment-events:at.payment.events}",
            groupId = "${spring.application.name}", autoStartup = "${at.orders.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> record) {
        EventHeaders headers = EventHeaders.of(record);
        if (headers.type() == null || !headers.type().startsWith("payments.intent.")) {
            return;
        }
        JsonNode payload = json.readTree(record.value());
        String orderId = payload.path("orderId").asString("");
        if (orderId.isEmpty()) {
            LOG.warn("payments event {} without orderId", headers.id());
            return;
        }
        UUID eventId = headers.id() == null ? UUID.nameUUIDFromBytes((record.topic() + ":" + record.partition() + ":"
                + record.offset()).getBytes(StandardCharsets.UTF_8)) : headers.id();
        payments.onIntentEvent(eventId, headers.type(), orderId, payload.path("failureMessage").asString(null));
    }
}
