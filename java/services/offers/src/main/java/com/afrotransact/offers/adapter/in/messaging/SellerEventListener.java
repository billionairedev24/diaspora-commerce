package com.afrotransact.offers.adapter.in.messaging;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.offers.application.SellerStandingService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes seller-service events: {@code seller.listings.hidden|restored} from {@code at.seller-compliance.events}
 * (suppress / restore every offer of the organisation) and {@code seller.org.approved|updated} from
 * {@code at.seller-org.events} (seller display name). Both topics are keyed by org id, so one organisation's events
 * arrive in order.
 */
@Component
class SellerEventListener {

    static final String LISTINGS_HIDDEN = "seller.listings.hidden";
    static final String LISTINGS_RESTORED = "seller.listings.restored";
    static final String ORG_APPROVED = "seller.org.approved";
    static final String ORG_UPDATED = "seller.org.updated";

    private static final Logger LOG = LoggerFactory.getLogger(SellerEventListener.class);

    private final SellerStandingService standings;
    private final JsonMapper json;

    SellerEventListener(SellerStandingService standings, JsonMapper json) {
        this.standings = standings;
        this.json = json;
    }

    /** The fields offers-service needs from SellerEnforcement and SellerOrgEvent payloads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SellerMessage(String orgId, String displayName) {
    }

    @KafkaListener(id = "offers-seller", groupId = "${spring.kafka.consumer.group-id:offers-service}",
            topics = {"${at.offers.consumers.seller-compliance-topic:at.seller-compliance.events}",
                "${at.offers.consumers.seller-org-topic:at.seller-org.events}"},
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        boolean listings = LISTINGS_HIDDEN.equals(type) || LISTINGS_RESTORED.equals(type);
        boolean org = ORG_APPROVED.equals(type) || ORG_UPDATED.equals(type);
        if (!listings && !org) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id header", type, rec.topic(),
                    rec.partition(), rec.offset());
            return;
        }
        SellerMessage m;
        try {
            m = json.readValue(rec.value(), SellerMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", type, eventId.get(), ex.getMessage());
            return;
        }
        if (m == null || m.orgId() == null || m.orgId().isBlank()) {
            LOG.warn("Skipping {} event {} without orgId", type, eventId.get());
            return;
        }
        if (listings) {
            standings.listingsVisibility(eventId.get(), m.orgId(), LISTINGS_HIDDEN.equals(type));
        } else {
            standings.orgProfile(eventId.get(), m.orgId(), m.displayName());
        }
    }
}
