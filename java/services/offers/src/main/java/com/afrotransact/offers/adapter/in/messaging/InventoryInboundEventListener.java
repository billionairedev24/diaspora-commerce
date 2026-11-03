package com.afrotransact.offers.adapter.in.messaging;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.offers.application.FbaOfferService;
import com.afrotransact.offers.application.FbaOfferService.ReceivedStock;
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
 * Consumes {@code inventory.inbound.received} from {@code at.inventory-inbound.events} (key = inbound shipment id):
 * received FBA stock switches the seller's listing to Fulfilled by AfroTransact (S257). Other inbound events are
 * ignored.
 */
@Component
class InventoryInboundEventListener {

    static final String INBOUND_RECEIVED = "inventory.inbound.received";

    private static final Logger LOG = LoggerFactory.getLogger(InventoryInboundEventListener.class);

    private final FbaOfferService fba;
    private final JsonMapper json;

    InventoryInboundEventListener(FbaOfferService fba, JsonMapper json) {
        this.fba = fba;
        this.json = json;
    }

    /** Wire format of inventory's InboundReceivedPayload (contracts/asyncapi/fragments/inventory.yaml). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record InboundReceivedMessage(String shipmentId, String source, String ownerOrg, String nodeId,
                                  List<LineMessage> lines) {
    }

    /** One received line. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LineMessage(String sku, String atin, Integer qtyGood) {
    }

    @KafkaListener(id = "offers-inventory-inbound", groupId = "${spring.kafka.consumer.group-id:offers-service}",
            topics = "${at.offers.consumers.inventory-inbound-topic:at.inventory-inbound.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!INBOUND_RECEIVED.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            throw MalformedEvent.missingEventId(type, rec);
        }
        InboundReceivedMessage m;
        try {
            m = json.readValue(rec.value(), InboundReceivedMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null || m.ownerOrg() == null || m.lines() == null) {
            LOG.warn("Skipping {} event {} with missing fields", type, eventId.get());
            return;
        }
        fba.stockReceived(eventId.get(), new ReceivedStock(m.shipmentId(), m.source(), m.ownerOrg(), m.nodeId(),
                m.lines().stream().filter(l -> l != null)
                        .map(l -> new ReceivedStock.Line(l.sku(), l.atin(), l.qtyGood() == null ? 0 : l.qtyGood()))
                        .toList()));
    }
}
