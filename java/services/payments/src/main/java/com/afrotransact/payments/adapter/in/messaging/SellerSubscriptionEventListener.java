package com.afrotransact.payments.adapter.in.messaging;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.application.OrgPlanService;
import com.afrotransact.payments.application.SubscriptionNotice;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code seller.subscription.changed} from {@code at.seller-subscription.events} (key = org id, so one
 * organisation's changes stay in order) to keep the organisations' current plans, whose fee-schedule plan rates then
 * apply. Other events of the topic are ignored; malformed messages are logged and skipped.
 */
@Component
class SellerSubscriptionEventListener {

    static final String SUBSCRIPTION_CHANGED = "seller.subscription.changed";

    private static final Logger LOG = LoggerFactory.getLogger(SellerSubscriptionEventListener.class);

    private final OrgPlanService plans;
    private final JsonMapper json;

    SellerSubscriptionEventListener(OrgPlanService plans, JsonMapper json) {
        this.plans = plans;
        this.json = json;
    }

    /** Wire format of seller.yaml SellerSubscriptionChanged (only what payments needs). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SubscriptionMessage(String orgId, String change, String planCode, String status, Instant occurredAt) {
    }

    @KafkaListener(id = "payments-seller-subscription",
            groupId = "${spring.kafka.consumer.group-id:payments-service}",
            topics = "${at.payments.consumers.seller-subscription-topic:at.seller-subscription.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!SUBSCRIPTION_CHANGED.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id header", type, rec.topic(),
                    rec.partition(), rec.offset());
            return;
        }
        SubscriptionMessage m;
        try {
            m = json.readValue(rec.value(), SubscriptionMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", type, eventId.get(), ex.getMessage());
            return;
        }
        if (m == null || m.orgId() == null || m.orgId().isBlank()) {
            LOG.warn("Skipping {} event {} without orgId", type, eventId.get());
            return;
        }
        plans.subscriptionChanged(new SubscriptionNotice(eventId.get(), m.orgId(), m.planCode(), m.status(),
                m.occurredAt()));
    }
}
