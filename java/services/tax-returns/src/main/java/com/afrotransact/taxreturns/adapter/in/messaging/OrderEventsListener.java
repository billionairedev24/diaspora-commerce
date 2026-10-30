package com.afrotransact.taxreturns.adapter.in.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.afrotransact.taxreturns.application.OrderIntakeService;
import com.afrotransact.taxreturns.application.OrderPlaced;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code orders.order.placed} (order snapshot + tax ledger) and {@code orders.shipment.delivered} (return
 * window starts) from {@code at.order.events}. Other order events are ignored. Malformed messages are logged and
 * skipped; use-case failures propagate so the container retries the record.
 */
@Component
class OrderEventsListener {

    static final String ORDER_PLACED = "orders.order.placed";
    static final String SHIPMENT_DELIVERED = "orders.shipment.delivered";

    private static final Logger LOG = LoggerFactory.getLogger(OrderEventsListener.class);

    private final OrderIntakeService intake;
    private final JsonMapper json;

    OrderEventsListener(OrderIntakeService intake, JsonMapper json) {
        this.intake = intake;
        this.json = json;
    }

    @KafkaListener(id = "taxreturns-orders", groupId = "${spring.kafka.consumer.group-id:tax-returns-service}",
            topics = "${at.taxreturns.consumers.order-topic:at.order.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!ORDER_PLACED.equals(type) && !SHIPMENT_DELIVERED.equals(type)) {
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
        if (ORDER_PLACED.equals(type)) {
            placed(eventId.get(), n);
        } else {
            delivered(eventId.get(), n);
        }
    }

    private void placed(UUID eventId, JsonNode n) {
        OrderPlaced event;
        try {
            List<OrderPlaced.Shipment> shipments = new ArrayList<>();
            for (JsonNode s : n.path("shipments")) {
                List<OrderPlaced.Line> lines = new ArrayList<>();
                for (JsonNode l : s.path("lines")) {
                    lines.add(new OrderPlaced.Line(Json.text(l, "lineId"), Json.text(l, "variantAtin"),
                            Json.text(l, "title"), l.path("quantity").asInt(1), l.path("unitPriceMinor").asLong(),
                            l.path("taxMinor").asLong(), Json.text(l, "productType"),
                            l.path("perishable").isBoolean() ? l.path("perishable").asBoolean() : null,
                            l.path("discountMinor").asLong(0), Json.text(l, "taxJurisdiction"),
                            Json.text(l, "localTaxJurisdiction"), l.path("shippingTaxMinor").asLong(0)));
                }
                shipments.add(new OrderPlaced.Shipment(Json.uuid(s, "shipmentId"), Json.text(s, "sellerOrgId"),
                        Json.text(s, "sellerName"), Json.text(s, "fulfilmentType"), Json.text(s, "deliveryMethod"),
                        s.path("totalMinor").asLong(), lines,
                        s.path("shippingMinor").isNumber() ? s.path("shippingMinor").asLong() : null));
            }
            String line1 = Json.text(n, "shipToLine1");
            if (line1 == null && n.path("shipments").size() > 0) {
                line1 = Json.text(n.path("shipments").get(0), "shipToLine1");
            }
            String line2 = Json.text(n, "shipToLine2");
            if (line2 == null && n.path("shipments").size() > 0) {
                line2 = Json.text(n.path("shipments").get(0), "shipToLine2");
            }
            event = new OrderPlaced(Json.uuid(n, "orderId"), Json.text(n, "orderNumber"),
                    Json.text(n, "customerId"), Json.text(n, "currency"), Json.instant(n, "placedAt"),
                    Json.text(n, "shipToCity"), Json.text(n, "shipToState"), Json.text(n, "shipToPostalCode"),
                    Json.text(n, "shipToCountry"), shipments, Json.text(n, "shipToName"), line1, line2,
                    Json.text(n, "paymentId"));
        } catch (IllegalArgumentException ex) {
            throw MalformedEvent.of("orders.order.placed", ex);
        }
        if (event.orderId() == null || event.orderNumber() == null || event.customerId() == null
                || event.placedAt() == null || event.shipments().stream().anyMatch(s -> s.shipmentId() == null
                || s.sellerOrgId() == null || s.lines().stream().anyMatch(l -> l.lineId() == null))) {
            LOG.warn("Skipping orders.order.placed {}: required fields missing", eventId);
            return;
        }
        intake.onOrderPlaced(eventId, event);
    }

    private void delivered(UUID eventId, JsonNode n) {
        UUID shipmentId;
        try {
            shipmentId = Json.uuid(n, "shipmentId");
        } catch (IllegalArgumentException ex) {
            shipmentId = null;
        }
        if (shipmentId == null || Json.instant(n, "deliveredAt") == null) {
            LOG.warn("Skipping orders.shipment.delivered {}: shipmentId / deliveredAt missing", eventId);
            return;
        }
        intake.onShipmentDelivered(eventId, shipmentId, Json.instant(n, "deliveredAt"));
    }
}
