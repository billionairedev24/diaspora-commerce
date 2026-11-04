package com.afrotransact.offers.adapter.in.messaging;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.offers.application.RetailListingSyncService;
import com.afrotransact.offers.application.RetailListingSyncService.RetailItem;
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
 * Consumes retail.item.listing-changed and retail.item.updated from {@code at.retail.events} (key = item id): AfroTransact
 * Retail's listing drives its first-party offer (S281). Other retail events are ignored.
 */
@Component
class RetailEventListener {

    static final Set<String> TYPES = Set.of("retail.item.listing-changed", "retail.item.updated");

    private static final Logger LOG = LoggerFactory.getLogger(RetailEventListener.class);

    private final RetailListingSyncService sync;
    private final JsonMapper json;

    RetailEventListener(RetailListingSyncService sync, JsonMapper json) {
        this.sync = sync;
        this.json = json;
    }

    /** Wire format of retail's RetailItemEvent (contracts/asyncapi/fragments/retail.yaml). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ItemMessage(UUID itemId, String sku, String atin, String listingStatus, Long priceMinor, String currency) {
    }

    @KafkaListener(id = "offers-retail", groupId = "${spring.kafka.consumer.group-id:offers-service}",
            topics = "${at.offers.consumers.retail-topic:at.retail.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!TYPES.contains(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            throw MalformedEvent.missingEventId(type, rec);
        }
        ItemMessage m;
        try {
            m = json.readValue(rec.value(), ItemMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null || m.sku() == null || m.listingStatus() == null) {
            LOG.warn("Skipping {} event {} with missing fields", type, eventId.get());
            return;
        }
        sync.itemChanged(eventId.get(), new RetailItem(m.itemId(), m.sku(), m.atin(), m.listingStatus(),
                m.priceMinor(), m.currency()));
    }
}
