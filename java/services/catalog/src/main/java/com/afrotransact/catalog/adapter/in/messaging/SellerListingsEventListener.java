package com.afrotransact.catalog.adapter.in.messaging;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.application.ListingEnforcement;
import com.afrotransact.catalog.application.ListingVisibilityService;
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
 * Consumes {@code seller.listings.hidden} / {@code seller.listings.restored} from {@code at.seller-compliance.events}
 * (key = org id, so hide and restore of one organisation stay in order). Other compliance events are ignored.
 * Malformed messages are logged and skipped; use-case failures propagate so the container retries the record.
 */
@Component
class SellerListingsEventListener {

    static final String LISTINGS_HIDDEN = "seller.listings.hidden";
    static final String LISTINGS_RESTORED = "seller.listings.restored";

    private static final Logger LOG = LoggerFactory.getLogger(SellerListingsEventListener.class);

    private final ListingVisibilityService visibility;
    private final JsonMapper json;

    SellerListingsEventListener(ListingVisibilityService visibility, JsonMapper json) {
        this.visibility = visibility;
        this.json = json;
    }

    /** The fields of seller-service's EnforcementPayload (seller.yaml SellerEnforcement) catalog needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnforcementMessage(String orgId, String reason, Instant occurredAt) {
    }

    @KafkaListener(id = "catalog-seller-compliance", groupId = "${spring.kafka.consumer.group-id:catalog-service}",
            topics = "${at.catalog.consumers.seller-compliance-topic:at.seller-compliance.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!LISTINGS_HIDDEN.equals(type) && !LISTINGS_RESTORED.equals(type)) {
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
        ListingEnforcement event = new ListingEnforcement(eventId.get(), m.orgId(), m.reason(), m.occurredAt());
        if (LISTINGS_HIDDEN.equals(type)) {
            visibility.hide(event);
        } else {
            visibility.restore(event);
        }
    }
}
