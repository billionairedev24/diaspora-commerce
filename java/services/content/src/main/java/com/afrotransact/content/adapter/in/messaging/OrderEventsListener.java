package com.afrotransact.content.adapter.in.messaging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.content.application.AdDeliveryService;
import com.afrotransact.content.domain.ads.PlacedOrder;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code orders.order.placed} from {@code at.order.events} to attribute sales to sponsored-product clicks
 * (last click by the same shopper on the ordered variant, same seller, within 7 days). Other order events are ignored.
 */
@Component
class OrderEventsListener {

    static final String ORDER_PLACED = "orders.order.placed";

    private static final Logger LOG = LoggerFactory.getLogger(OrderEventsListener.class);

    private final AdDeliveryService delivery;
    private final JsonMapper json;

    OrderEventsListener(AdDeliveryService delivery, JsonMapper json) {
        this.delivery = delivery;
        this.json = json;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LineMessage(String lineId, String variantAtin, Integer quantity, Long unitPriceMinor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ShipmentMessage(String sellerOrgId, List<LineMessage> lines) {
    }

    /** orders.yaml OrdersOrderPlaced (fields content needs). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderPlacedMessage(String orderId, String customerId, Instant placedAt, List<ShipmentMessage> shipments) {
    }

    @KafkaListener(id = "content-order-events", groupId = "${spring.kafka.consumer.group-id:content-service}",
            topics = "${at.content.consumers.orders-topic:at.order.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        if (!ORDER_PLACED.equals(EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse(""))) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id header", ORDER_PLACED, rec.topic(),
                    rec.partition(), rec.offset());
            return;
        }
        OrderPlacedMessage m;
        try {
            m = json.readValue(rec.value(), OrderPlacedMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", ORDER_PLACED, eventId.get(), ex.getMessage());
            return;
        }
        if (m == null || m.orderId() == null || m.placedAt() == null || m.shipments() == null) {
            LOG.warn("Skipping {} event {} with missing fields", ORDER_PLACED, eventId.get());
            return;
        }
        List<PlacedOrder.Line> lines = new ArrayList<>();
        for (ShipmentMessage s : m.shipments()) {
            if (s == null || s.lines() == null || s.sellerOrgId() == null) {
                continue;
            }
            for (LineMessage l : s.lines()) {
                if (l == null || l.lineId() == null || l.variantAtin() == null) {
                    continue;
                }
                long qty = l.quantity() == null ? 1 : l.quantity();
                long unit = l.unitPriceMinor() == null ? 0 : l.unitPriceMinor();
                lines.add(new PlacedOrder.Line(l.lineId(), s.sellerOrgId(), l.variantAtin(), qty * unit));
            }
        }
        delivery.orderPlaced(eventId.get(), new PlacedOrder(m.orderId(), m.customerId(), m.placedAt(), lines));
    }
}
