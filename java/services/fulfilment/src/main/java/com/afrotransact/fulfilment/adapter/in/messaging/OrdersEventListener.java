package com.afrotransact.fulfilment.adapter.in.messaging;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.fulfilment.application.PlacedOrder;
import com.afrotransact.fulfilment.application.ShipmentIntakeService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code orders.order.placed} and {@code orders.shipment.cancelled} from {@code at.order.events}
 * (key = order id). Other order events are ignored. Malformed messages are logged and skipped; use-case failures
 * propagate so the container retries the record (the inbox makes the retry safe).
 */
@Component
class OrdersEventListener {

    static final String ORDER_PLACED = "orders.order.placed";
    static final String SHIPMENT_CANCELLED = "orders.shipment.cancelled";

    private static final Logger LOG = LoggerFactory.getLogger(OrdersEventListener.class);

    private final ShipmentIntakeService intake;
    private final JsonMapper json;

    OrdersEventListener(ShipmentIntakeService intake, JsonMapper json) {
        this.intake = intake;
        this.json = json;
    }

    /** orders.yaml OrdersOrderPlaced (+ optional perishable / nodeId / ship-to position when orders sends them). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderPlacedMessage(String orderId, String orderNumber, String customerId, String shipToCity,
                              String shipToState, String shipToPostalCode, String shipToCountry, Double shipToLat,
                              Double shipToLon, List<ShipmentMessage> shipments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ShipmentMessage(String shipmentId, String sellerOrgId, String sellerName, String fulfilmentType,
                           String deliveryMethod, LocalDate promisedBy, boolean handoverRequired, Boolean perishable,
                           String nodeId, List<LineMessage> lines, LocalDate shipBy, Double lat, Double lng,
                           PickupMessage pickup) {
    }

    /** S255: the seller's pickup location of an AfroTransact Delivery from the seller's store. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PickupMessage(String locationId, Double lat, Double lng) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LineMessage(String lineId, String variantAtin, String title, int quantity, String sellerOrgId,
                       String sellerName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ShipmentCancelledMessage(String shipmentId, String reason) {
    }

    @KafkaListener(id = "fulfilment-orders", groupId = "${spring.kafka.consumer.group-id:fulfilment-service}",
            topics = "${at.fulfilment.consumers.orders-topic:at.order.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!ORDER_PLACED.equals(type) && !SHIPMENT_CANCELLED.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id header", type, rec.topic(),
                    rec.partition(), rec.offset());
            return;
        }
        try {
            if (ORDER_PLACED.equals(type)) {
                OrderPlacedMessage m = json.readValue(rec.value(), OrderPlacedMessage.class);
                if (m == null || m.orderId() == null) {
                    LOG.warn("Skipping {} event {} without orderId", type, eventId.get());
                    return;
                }
                intake.orderPlaced(eventId.get(), toOrder(m));
            } else {
                ShipmentCancelledMessage m = json.readValue(rec.value(), ShipmentCancelledMessage.class);
                if (m == null || m.shipmentId() == null) {
                    LOG.warn("Skipping {} event {} without shipmentId", type, eventId.get());
                    return;
                }
                intake.shipmentCancelled(eventId.get(), m.shipmentId(), m.reason());
            }
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", type, eventId.get(), ex.getMessage());
        }
    }

    static PlacedOrder toOrder(OrderPlacedMessage m) {
        List<PlacedOrder.PlacedShipment> shipments = m.shipments() == null ? List.of() : m.shipments().stream()
                .map(s -> new PlacedOrder.PlacedShipment(s.shipmentId(), s.sellerOrgId(), s.sellerName(),
                        s.fulfilmentType(), s.deliveryMethod(), s.promisedBy(), s.handoverRequired(), s.perishable(),
                        s.nodeId(), s.lines() == null ? List.of() : s.lines().stream()
                        .map(l -> new PlacedOrder.PlacedLine(l.lineId(), l.variantAtin(), l.title(), l.quantity(),
                                l.sellerOrgId(), l.sellerName()))
                        .toList(), s.shipBy(), s.pickup() == null ? null
                        : new PlacedOrder.SellerPickup(s.pickup().locationId(), s.pickup().lat(), s.pickup().lng())))
                .toList();
        // orders sends the ship-to position per shipment (lat / lng); older producers sent it on the order.
        ShipmentMessage positioned = m.shipments() == null ? null : m.shipments().stream()
                .filter(s -> s.lat() != null && s.lng() != null).findFirst().orElse(null);
        Double lat = m.shipToLat() != null ? m.shipToLat() : positioned == null ? null : positioned.lat();
        Double lon = m.shipToLon() != null ? m.shipToLon() : positioned == null ? null : positioned.lng();
        return new PlacedOrder(m.orderId(), m.orderNumber(), m.customerId(), m.shipToCity(), m.shipToState(),
                m.shipToPostalCode(), m.shipToCountry(), lat, lon, shipments);
    }
}
