package com.afrotransact.offers.adapter.in.messaging;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.offers.application.StockSyncService;
import com.afrotransact.offers.port.StockRepository.StockLevel;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code inventory.availability.changed} from {@code at.inventory.events} (key = SKU) into the stock
 * projection; lot and other events on the topic are ignored. Older versions per (sku, node) are discarded.
 */
@Component
class InventoryEventListener {

    static final String AVAILABILITY_CHANGED = "inventory.availability.changed";

    private static final Logger LOG = LoggerFactory.getLogger(InventoryEventListener.class);

    private final StockSyncService sync;
    private final JsonMapper json;

    InventoryEventListener(StockSyncService sync, JsonMapper json) {
        this.sync = sync;
        this.json = json;
    }

    /** Wire format of inventory's AvailabilityChangedPayload (contracts/asyncapi/fragments/inventory.yaml). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AvailabilityMessage(String sku, String nodeId, String ownerOrg, Integer available, Long version) {
    }

    @KafkaListener(id = "offers-inventory", groupId = "${spring.kafka.consumer.group-id:offers-service}",
            topics = "${at.offers.consumers.inventory-topic:at.inventory.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!AVAILABILITY_CHANGED.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id header", type, rec.topic(),
                    rec.partition(), rec.offset());
            return;
        }
        AvailabilityMessage m;
        try {
            m = json.readValue(rec.value(), AvailabilityMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", type, eventId.get(), ex.getMessage());
            return;
        }
        if (m == null || m.sku() == null || m.nodeId() == null || m.ownerOrg() == null || m.available() == null
                || m.version() == null) {
            LOG.warn("Skipping {} event {} with missing fields", type, eventId.get());
            return;
        }
        sync.availabilityChanged(eventId.get(), new StockLevel(m.sku(), m.nodeId(), m.ownerOrg(), m.available(),
                m.version()));
    }
}
