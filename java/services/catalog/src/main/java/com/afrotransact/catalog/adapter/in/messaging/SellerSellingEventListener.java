package com.afrotransact.catalog.adapter.in.messaging;

import java.util.List;

import com.afrotransact.catalog.application.SellingHoldService;
import com.afrotransact.catalog.application.SellingHoldService.Change;
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
 * Consumes {@code seller.selling.listings.paused}, {@code .restored} and {@code seller.selling.rule.changed} from
 * {@code at.seller-compliance.events}. Its own consumer group, so it sees every record next to
 * {@link SellerListingsEventListener}; the handlers are idempotent, so a redelivery changes nothing. Malformed messages
 * are logged and skipped; use-case failures propagate so the container retries the record.
 */
@Component
class SellerSellingEventListener {

    static final String PAUSED = "seller.selling.listings.paused";
    static final String RESTORED = "seller.selling.listings.restored";
    static final String RULE_CHANGED = "seller.selling.rule.changed";

    private static final Logger LOG = LoggerFactory.getLogger(SellerSellingEventListener.class);

    private final SellingHoldService holds;
    private final JsonMapper json;

    SellerSellingEventListener(SellingHoldService holds, JsonMapper json) {
        this.holds = holds;
        this.json = json;
    }

    /** The fields of seller-service's SellingEvent (seller.yaml) catalog needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String orgId, String scopeType, String scopeKey, String reason, List<String> categoryCodes) {
    }

    @KafkaListener(id = "catalog-seller-selling", groupId = "${spring.kafka.consumer.group-id:catalog-service}-selling",
            topics = "${at.catalog.consumers.seller-compliance-topic:at.seller-compliance.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!PAUSED.equals(type) && !RESTORED.equals(type) && !RULE_CHANGED.equals(type)) {
            return;
        }
        Message m;
        try {
            m = json.readValue(rec.value(), Message.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null || m.scopeType() == null || m.scopeKey() == null) {
            LOG.warn("Skipping {} event without a scope", type);
            return;
        }
        Change change = new Change(m.orgId(), m.scopeType(), m.scopeKey(), m.reason(), m.categoryCodes());
        switch (type) {
            case PAUSED -> holds.pause(change);
            case RESTORED -> holds.restore(change);
            default -> holds.ruleChanged(change);
        }
    }
}
