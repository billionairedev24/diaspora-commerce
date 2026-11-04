package com.afrotransact.inventory.adapter.in.messaging;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.inventory.application.OfferLinkSyncService;
import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code offers.offer.created|updated|withdrawn} from {@code at.offers.events}: which FBA stock has a live
 * Fulfilled-by-AfroTransact offer (stranded stock, S067). Buy box and coupon events are ignored.
 */
@Component
class OffersEventListener {

    static final Set<String> OFFER_EVENTS = Set.of("offers.offer.created", "offers.offer.updated",
            "offers.offer.withdrawn");
    static final String OCCURRED_AT = "occurred-at";


    private final OfferLinkSyncService links;
    private final JsonMapper json;

    OffersEventListener(OfferLinkSyncService links, JsonMapper json) {
        this.links = links;
        this.json = json;
    }

    /** Wire format of offers' OfferEventPayload. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OfferMessage(UUID offerId, String atin, String sellerOrgId, String inventorySku, String fulfilment,
                        String status, Boolean productLive) {
    }

    @KafkaListener(id = "inventory-offers", groupId = "${spring.kafka.consumer.group-id:inventory-service}",
            topics = "${at.inventory.consumers.offers-topic:at.offers.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!OFFER_EVENTS.contains(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            throw MalformedEvent.missingEventId(type, rec);
        }
        OfferMessage m;
        try {
            m = json.readValue(rec.value(), OfferMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null || m.offerId() == null || m.sellerOrgId() == null) {
            return;
        }
        Instant at = EventHeaders.text(rec, OCCURRED_AT).flatMap(OffersEventListener::instant)
                .orElse(Instant.ofEpochMilli(rec.timestamp() > 0 ? rec.timestamp() : System.currentTimeMillis()));
        links.offerChanged(eventId.get(), new OfferLinkSyncService.OfferState(m.offerId(), m.sellerOrgId(), m.atin(),
                m.inventorySku(), m.fulfilment(), "offers.offer.withdrawn".equals(type) ? "WITHDRAWN" : m.status(),
                Boolean.TRUE.equals(m.productLive()), at));
    }

    private static Optional<Instant> instant(String v) {
        try {
            return Optional.of(Instant.parse(v));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
