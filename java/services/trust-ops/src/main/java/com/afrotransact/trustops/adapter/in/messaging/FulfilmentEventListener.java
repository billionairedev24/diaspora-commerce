package com.afrotransact.trustops.adapter.in.messaging;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.afrotransact.trustops.application.SellerHealthService;
import com.afrotransact.trustops.port.ShipmentFact;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seller handovers from fulfilment ({@code at.fulfilment.events}, key = shipment id): a SELLER-origin
 * {@code fulfilment.shipment.handed_over} records when the shipment left the seller and its ship-by date, which seller
 * health compares for the late-shipment rate. FC loadouts (origin FC) are not the seller's lateness and are skipped.
 */
@Component
class FulfilmentEventListener {

    static final String HANDED_OVER = "fulfilment.shipment.handed_over";
    static final String SELLER_ORIGIN = "SELLER";


    private final SellerHealthService health;
    private final JsonMapper json;

    FulfilmentEventListener(SellerHealthService health, JsonMapper json) {
        this.health = health;
        this.json = json;
    }

    @KafkaListener(id = "trust-ops-fulfilment",
            groupId = "${spring.kafka.consumer.group-id:trust-ops-service}-fulfilment",
            topics = "${at.trustops.consumers.fulfilment-topic:at.fulfilment.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.type(rec);
        if (!HANDED_OVER.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            throw MalformedEvent.missingEventId(type, rec);
        }
        List<ShipmentFact> facts;
        try {
            facts = facts(json.readTree(rec.value()));
        } catch (JacksonException | IllegalArgumentException | NullPointerException | DateTimeParseException ex) {
            throw MalformedEvent.of(type, ex);
        }
        health.recordHandovers(eventId.get(), facts);
    }

    static List<ShipmentFact> facts(JsonNode n) {
        String shipment = text(n, "shipmentId");
        String seller = text(n, "sellerOrgId");
        Instant handedOverAt = instant(n, "handedOverAt");
        if (!SELLER_ORIGIN.equalsIgnoreCase(text(n, "origin")) || shipment == null || seller == null
                || handedOverAt == null) {
            return List.of();
        }
        return List.of(new ShipmentFact(shipment, text(n, "orderId"), seller, null, null, null, null, null, null,
                date(n, "shipBy"), handedOverAt));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asString(null);
    }

    private static Instant instant(JsonNode n, String field) {
        String v = text(n, field);
        return v == null ? null : Instant.parse(v);
    }

    private static LocalDate date(JsonNode n, String field) {
        String v = text(n, field);
        return v == null ? null : LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
    }
}
