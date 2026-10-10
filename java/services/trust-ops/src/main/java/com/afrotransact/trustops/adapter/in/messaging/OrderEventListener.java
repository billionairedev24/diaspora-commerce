package com.afrotransact.trustops.adapter.in.messaging;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.trustops.application.SellerHealthService;
import com.afrotransact.trustops.port.ShipmentFact;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Projects orders events ({@code at.order.events}, key = order id) into per-shipment facts for seller health:
 * placed (with the promised date), delivered, cancelled (and by whom) and status changes.
 */
@Component
class OrderEventListener {

    static final String PLACED = "orders.order.placed";
    static final String DELIVERED = "orders.shipment.delivered";
    static final String CANCELLED = "orders.shipment.cancelled";
    static final String STATUS_CHANGED = "orders.shipment.status_changed";

    private static final Logger LOG = LoggerFactory.getLogger(OrderEventListener.class);

    private final SellerHealthService health;
    private final JsonMapper json;

    OrderEventListener(SellerHealthService health, JsonMapper json) {
        this.health = health;
        this.json = json;
    }

    @KafkaListener(id = "trust-ops-seller-health",
            groupId = "${spring.kafka.consumer.group-id:trust-ops-service}-seller-health",
            topics = "${at.trustops.consumers.order-topic:at.order.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.type(rec);
        if (!List.of(PLACED, DELIVERED, CANCELLED, STATUS_CHANGED).contains(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id", type, rec.topic(), rec.partition(),
                    rec.offset());
            return;
        }
        List<ShipmentFact> facts;
        try {
            facts = facts(type, json.readTree(rec.value()));
        } catch (JacksonException | IllegalArgumentException | NullPointerException | DateTimeParseException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", type, eventId.get(), ex.getMessage());
            return;
        }
        health.recordShipments(eventId.get(), facts);
    }

    static List<ShipmentFact> facts(String type, JsonNode n) {
        String orderId = text(n, "orderId");
        List<ShipmentFact> out = new ArrayList<>();
        switch (type) {
            case PLACED -> {
                Instant placedAt = instant(n, "placedAt");
                for (JsonNode s : n.path("shipments")) {
                    if (text(s, "shipmentId") != null && text(s, "sellerOrgId") != null) {
                        out.add(new ShipmentFact(text(s, "shipmentId"), orderId, text(s, "sellerOrgId"), placedAt,
                                date(s, "promisedBy"), null, null, null, "placed"));
                    }
                }
            }
            case DELIVERED -> add(out, n, orderId, null, instant(n, "deliveredAt"), null, null, "delivered");
            case CANCELLED -> add(out, n, orderId, null, null, instant(n, "cancelledAt"), text(n, "actorType"),
                    "cancelled");
            case STATUS_CHANGED -> {
                String to = text(n, "to");
                Instant at = instant(n, "occurredAt");
                add(out, n, orderId, date(n, "promisedBy"), "delivered".equals(to) ? at : null,
                        "cancelled".equals(to) ? at : null, "cancelled".equals(to) ? text(n, "actorType") : null,
                        to);
            }
            default -> {
                // not a shipment event
            }
        }
        return out;
    }

    private static void add(List<ShipmentFact> out, JsonNode n, String orderId, LocalDate promisedBy,
                            Instant deliveredAt, Instant cancelledAt, String cancelledBy, String status) {
        String shipment = text(n, "shipmentId");
        String seller = text(n, "sellerOrgId");
        if (shipment != null && seller != null) {
            out.add(new ShipmentFact(shipment, orderId, seller, null, promisedBy, deliveredAt, cancelledAt,
                    cancelledBy, status));
        }
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
