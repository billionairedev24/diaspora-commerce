package com.afrotransact.offers.adapter.in.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.offers.application.OrderStockService;
import com.afrotransact.offers.application.OrderStockService.SoldLine;
import com.afrotransact.platform.events.consumer.MalformedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code orders.order.placed} and {@code orders.shipment.cancelled} from {@code at.order.events}: units sold
 * by sellers who ship themselves (SELLER_FULFILLED shipments) leave their offer's quantity, and come back when a
 * placed shipment is cancelled. Other order events, other fulfilment types and pre-orders are ignored.
 */
@Component
class OrderStockEventListener {

    static final String ORDER_PLACED = "orders.order.placed";
    static final String SHIPMENT_CANCELLED = "orders.shipment.cancelled";
    static final String SELLER_FULFILLED = "SELLER_FULFILLED";


    private final OrderStockService stock;
    private final JsonMapper json;

    OrderStockEventListener(OrderStockService stock, JsonMapper json) {
        this.stock = stock;
        this.json = json;
    }

    @KafkaListener(id = "offers-order-stock", groupId = "${spring.kafka.consumer.group-id:offers-service}",
            topics = "${at.offers.consumers.order-topic:at.order.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!ORDER_PLACED.equals(type) && !SHIPMENT_CANCELLED.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            throw MalformedEvent.missingEventId(type, rec);
        }
        JsonNode n;
        try {
            n = json.readTree(rec.value());
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        handle(type, eventId.get(), n);
    }

    /** Dispatches one parsed event (package-private for tests). */
    void handle(String type, UUID eventId, JsonNode n) {
        List<SoldLine> lines = new ArrayList<>();
        if (ORDER_PLACED.equals(type)) {
            for (JsonNode s : n.path("shipments")) {
                if (SELLER_FULFILLED.equals(s.path("fulfilmentType").asString(""))) {
                    collect(lines, s.path("sellerOrgId").asString(null), s.path("lines"));
                }
            }
            stock.orderPlaced(eventId, lines);
        } else {
            if (SELLER_FULFILLED.equals(n.path("fulfilmentType").asString(""))) {
                collect(lines, n.path("sellerOrgId").asString(null), n.path("items"));
            }
            stock.shipmentCancelled(eventId, lines);
        }
    }

    private static void collect(List<SoldLine> out, String sellerOrgId, JsonNode items) {
        for (JsonNode l : items) {
            String atin = l.path("variantAtin").asString(null);
            int qty = l.path("quantity").asInt(0);
            if (sellerOrgId != null && atin != null && qty > 0) {
                out.add(new SoldLine(sellerOrgId, atin, qty));
            }
        }
    }
}
