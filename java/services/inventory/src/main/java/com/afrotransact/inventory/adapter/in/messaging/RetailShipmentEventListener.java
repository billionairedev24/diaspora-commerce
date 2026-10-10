package com.afrotransact.inventory.adapter.in.messaging;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.inventory.application.InboundCommands;
import com.afrotransact.inventory.application.InboundShipmentService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code retail.shipment.stage-changed} from {@code at.retail.events} (S226): a retail purchase-order shipment
 * becomes an inbound notice at departure and is closed when retail marks it received. Other retail events are
 * ignored.
 */
@Component
class RetailShipmentEventListener {

    static final String STAGE_CHANGED = "retail.shipment.stage-changed";

    private static final Logger LOG = LoggerFactory.getLogger(RetailShipmentEventListener.class);

    private final InboundShipmentService inbound;
    private final JsonMapper json;

    RetailShipmentEventListener(InboundShipmentService inbound, JsonMapper json) {
        this.inbound = inbound;
        this.json = json;
    }

    /** Wire format of retail's ShipmentEvent (contracts/asyncapi/fragments/retail.yaml). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ShipmentMessage(String shipmentId, String reference, String stage, String nodeId, LocalDate eta,
                           List<LineMessage> lines) {
    }

    /** One ASN SKU. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LineMessage(String sku, Integer qty) {
    }

    @KafkaListener(id = "inventory-retail-shipments", groupId = "${spring.kafka.consumer.group-id:inventory-service}",
            topics = "${at.inventory.consumers.retail-topic:at.retail.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!STAGE_CHANGED.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id header", type, rec.topic(),
                    rec.partition(), rec.offset());
            return;
        }
        ShipmentMessage m;
        try {
            m = json.readValue(rec.value(), ShipmentMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", type, eventId.get(), ex.getMessage());
            return;
        }
        if (m == null || m.reference() == null || m.stage() == null) {
            LOG.warn("Skipping {} event {} with missing fields", type, eventId.get());
            return;
        }
        List<InboundCommands.NoticeLine> lines = m.lines() == null ? List.of() : m.lines().stream()
                .filter(l -> l != null && l.sku() != null && l.qty() != null)
                .map(l -> new InboundCommands.NoticeLine(l.sku(), l.qty())).toList();
        inbound.retailShipmentChanged(eventId.get(), new InboundCommands.RetailShipmentNotice(m.shipmentId(),
                m.reference(), m.stage(), m.nodeId(), m.eta(), lines));
    }
}
