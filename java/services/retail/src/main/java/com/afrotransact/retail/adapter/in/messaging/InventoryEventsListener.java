package com.afrotransact.retail.adapter.in.messaging;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.afrotransact.retail.application.CogsPostingService;
import com.afrotransact.retail.application.ShippedLine;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code at.inventory-reservation.events}: when a reservation is committed (order shipped) the lots' landed
 * cost of 1P SKUs is posted as COGS. Other event types are ignored; duplicates are skipped by the inbox.
 */
@Component
class InventoryEventsListener {

    static final String TOPIC = "at.inventory-reservation.events";
    static final String COMMITTED = "inventory.reservation.committed";
    private static final Logger LOG = LoggerFactory.getLogger(InventoryEventsListener.class);

    private final CogsPostingService cogs;
    private final JsonMapper json;

    InventoryEventsListener(CogsPostingService cogs, JsonMapper json) {
        this.cogs = cogs;
        this.json = json;
    }

    @KafkaListener(id = "retail-cogs", topics = TOPIC, groupId = "retail-service",
            autoStartup = "${at.retail.consumers.enabled:true}")
    void onReservationEvent(ConsumerRecord<String, String> record) {
        String type = header(record, "event-type");
        if (!COMMITTED.equals(type)) {
            return;
        }
        UUID eventId = eventId(record, type);
        try {
            handle(eventId, record.value());
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
    }

    /** Parses an {@code inventory.reservation.committed} payload and posts COGS. */
    void handle(UUID eventId, String payload) {
        JsonNode root = json.readTree(payload);
        List<ShippedLine> lines = new ArrayList<>();
        for (JsonNode line : root.path("lines")) {
            List<ShippedLine.LotCost> lots = new ArrayList<>();
            for (JsonNode a : line.path("allocations")) {
                String currency = a.path("currency").asString(null);
                long unitCost = a.path("unitLandedCostMinor").asLong();
                Long usd = a.has("unitCostUsdMinor") ? a.path("unitCostUsdMinor").asLong()
                        : "USD".equals(currency) ? unitCost : null;
                lots.add(new ShippedLine.LotCost(a.path("lotNo").asString(null), a.path("qty").asInt(), unitCost,
                        currency, usd));
            }
            lines.add(new ShippedLine(line.path("sku").asString(), line.path("qty").asInt(), lots));
        }
        String orderId = root.path("orderId").asString();
        cogs.onShipped(eventId, orderId, lines)
                .ifPresent(e -> LOG.info("COGS {} posted for order {}: {}", e.number(), orderId, e.amountMinor()));
    }

    private static UUID eventId(ConsumerRecord<String, String> record, String type) {
        String raw = header(record, "event-id");
        if (raw != null) {
            try {
                return UUID.fromString(raw);
            } catch (IllegalArgumentException ex) {
                throw MalformedEvent.missingEventId(type, record);
            }
        }
        throw MalformedEvent.missingEventId(type, record);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
