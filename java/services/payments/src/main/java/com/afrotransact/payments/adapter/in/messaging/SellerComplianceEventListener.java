package com.afrotransact.payments.adapter.in.messaging;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.application.EnforcementNotice;
import com.afrotransact.payments.application.SellerEnforcementService;
import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code seller.payouts.paused} / {@code seller.payouts.resumed} from {@code at.seller-compliance.events}
 * (key = org id, so pause and resume of one organisation stay in order). Other compliance events are ignored.
 * Malformed messages are logged and skipped; use-case failures propagate so the container retries the record.
 */
@Component
class SellerComplianceEventListener {

    static final String PAYOUTS_PAUSED = "seller.payouts.paused";
    static final String PAYOUTS_RESUMED = "seller.payouts.resumed";

    private static final Logger LOG = LoggerFactory.getLogger(SellerComplianceEventListener.class);

    private final SellerEnforcementService enforcement;
    private final JsonMapper json;

    SellerComplianceEventListener(SellerEnforcementService enforcement, JsonMapper json) {
        this.enforcement = enforcement;
        this.json = json;
    }

    /** Wire format of seller-service's EnforcementPayload (seller.yaml SellerEnforcement). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnforcementMessage(String orgId, String accountId, String reason, String actorId, String note,
                              Instant occurredAt) {
    }

    @KafkaListener(id = "payments-seller-compliance", groupId = "${spring.kafka.consumer.group-id:payments-service}",
            topics = "${at.payments.consumers.seller-compliance-topic:at.seller-compliance.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!PAYOUTS_PAUSED.equals(type) && !PAYOUTS_RESUMED.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            throw MalformedEvent.missingEventId(type, rec);
        }
        EnforcementMessage m;
        try {
            m = json.readValue(rec.value(), EnforcementMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null || m.orgId() == null || m.orgId().isBlank()) {
            LOG.warn("Skipping {} event {} without orgId", type, eventId.get());
            return;
        }
        EnforcementNotice notice = new EnforcementNotice(eventId.get(), m.orgId(), m.accountId(), m.reason(),
                m.actorId(), m.note(), m.occurredAt());
        if (PAYOUTS_PAUSED.equals(type)) {
            enforcement.payoutsPaused(notice);
        } else {
            enforcement.payoutsResumed(notice);
        }
    }
}
