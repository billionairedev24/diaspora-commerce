package com.afrotransact.catalog.adapter.in.messaging;

import java.util.Set;

import com.afrotransact.catalog.application.ListingStockSyncService;
import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code offers.offer.created|updated} from {@code at.offers.events} (key = variant ATIN, so one variant's
 * changes stay in order): the declared quantity of a seller-fulfilled offer (SELLER, AFROTRANSACT_DELIVERY) is copied
 * onto the catalog variant. Setting a number is idempotent, so no inbox is needed. Fulfilled by AfroTransact offers
 * (stock is inventory's) and withdrawn offers are ignored.
 */
@Component
class OfferStockEventListener {

    static final Set<String> TYPES = Set.of("offers.offer.created", "offers.offer.updated");


    private final ListingStockSyncService sync;
    private final JsonMapper json;

    OfferStockEventListener(ListingStockSyncService sync, JsonMapper json) {
        this.sync = sync;
        this.json = json;
    }

    /** Wire format of offers' OfferEventPayload (the fields catalog needs). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OfferMessage(String atin, String sellerOrgId, String fulfilment, String status, Integer sellerQuantity) {
    }

    @KafkaListener(id = "catalog-offer-stock", groupId = "${spring.kafka.consumer.group-id:catalog-service}",
            topics = "${at.catalog.consumers.offers-topic:at.offers.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!TYPES.contains(type)) {
            return;
        }
        OfferMessage m;
        try {
            m = json.readValue(rec.value(), OfferMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null || m.atin() == null || m.sellerOrgId() == null || m.sellerQuantity() == null
                || "WITHDRAWN".equals(m.status()) || "AFROTRANSACT".equals(m.fulfilment())) {
            return;
        }
        sync.mirror(m.sellerOrgId(), m.atin(), m.sellerQuantity());
    }
}
