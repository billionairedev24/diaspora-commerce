package com.afrotransact.taxreturns.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.taxreturns.application.OrderIntakeService;
import com.afrotransact.taxreturns.application.OrderPlaced;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OrderEventsListenerTest {

    private final List<OrderPlaced> placed = new ArrayList<>();
    private final List<UUID> delivered = new ArrayList<>();
    private final OrderEventsListener listener = new OrderEventsListener(new OrderIntakeService() {
        @Override
        public boolean onOrderPlaced(UUID eventId, OrderPlaced event) {
            placed.add(event);
            return true;
        }

        @Override
        public boolean onShipmentDelivered(UUID eventId, UUID shipmentId, Instant deliveredAt) {
            delivered.add(shipmentId);
            return true;
        }
    }, JsonMapper.builder().build());

    static ConsumerRecord<String, String> rec(String topic, String type, String eventId, String json) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>(topic, 0, 0L, "k", json);
        r.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        if (eventId != null) {
            r.headers().add(EventHeaders.EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    @Test
    void mapsOrderPlacedWithShipmentsAndLines() {
        UUID order = UUID.randomUUID();
        UUID shipment = UUID.randomUUID();
        listener.onMessage(rec("at.order.events", "orders.order.placed", UUID.randomUUID().toString(), """
                {"orderId":"%s","orderNumber":"AT-2026-7KQ3MD","customerId":"cust_1","currency":"USD",
                 "itemsMinor":6499,"shippingMinor":799,"taxMinor":536,"discountMinor":0,"totalMinor":7834,
                 "placedAt":"2026-10-01T10:00:00Z","shipToCity":"Houston","shipToState":"TX",
                 "shipToPostalCode":"77019","shipToCountry":"US",
                 "shipments":[{"shipmentId":"%s","sellerOrgId":"org_a","sellerName":"Lagos Textile Hub",
                   "fulfilmentType":"FBA_AFROTRANSACT","deliveryMethod":"CARRIER","totalMinor":7834,
                   "lines":[{"lineId":"%s","variantAtin":"AT12345678","title":"Ankara dress","quantity":1,
                             "unitPriceMinor":6499,"taxMinor":536}]}]}
                """.formatted(order, shipment, UUID.randomUUID())));
        assertThat(placed).hasSize(1);
        OrderPlaced p = placed.getFirst();
        assertThat(p.orderId()).isEqualTo(order);
        assertThat(p.shipToState()).isEqualTo("TX");
        assertThat(p.placedAt()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(p.shipments().getFirst().fulfilmentType()).isEqualTo("FBA_AFROTRANSACT");
        assertThat(p.shipments().getFirst().lines().getFirst().taxMinor()).isEqualTo(536);
    }

    @Test
    void routesDeliveredAndIgnoresOthers() {
        UUID shipment = UUID.randomUUID();
        listener.onMessage(rec("at.order.events", "orders.shipment.delivered", UUID.randomUUID().toString(),
                "{\"shipmentId\":\"" + shipment + "\",\"deliveredAt\":\"2026-10-03T12:00:00Z\"}"));
        listener.onMessage(rec("at.order.events", "orders.shipment.status_changed", UUID.randomUUID().toString(),
                "{}"));
        assertThat(delivered).containsExactly(shipment);
    }

    @Test
    void skipsUnusableMessages() {
        listener.onMessage(rec("at.order.events", "orders.order.placed", null, "{}"));
        listener.onMessage(rec("at.order.events", "orders.order.placed", UUID.randomUUID().toString(), "nope"));
        listener.onMessage(rec("at.order.events", "orders.order.placed", UUID.randomUUID().toString(),
                "{\"orderId\":\"not-a-uuid\"}"));
        listener.onMessage(rec("at.order.events", "orders.order.placed", UUID.randomUUID().toString(),
                "{\"orderId\":\"" + UUID.randomUUID() + "\"}"));
        listener.onMessage(rec("at.order.events", "orders.shipment.delivered", UUID.randomUUID().toString(),
                "{\"shipmentId\":\"x\"}"));
        assertThat(placed).isEmpty();
        assertThat(delivered).isEmpty();
    }
}
