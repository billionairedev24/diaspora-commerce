package com.afrotransact.seller.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.application.SellerMetricsService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** S296: order, booking and offer events become reward metrics (listener called directly). */
class RewardMetricsEventListenerTest {

    private final List<String> calls = new ArrayList<>();
    private final SellerMetricsService fake = new SellerMetricsService() {
        @Override
        public boolean shipmentDelivered(UUID eventId, String orgId, long totalMinor, String currency, Instant at) {
            calls.add("sale " + orgId + " " + totalMinor + " " + currency + " " + at);
            return true;
        }

        @Override
        public boolean bookingCompleted(UUID eventId, String orgId, Instant at) {
            calls.add("booking " + orgId);
            return true;
        }

        @Override
        public boolean offerChanged(UUID eventId, String offerId, String orgId, boolean live, Instant at) {
            calls.add("offer " + offerId + " " + orgId + " " + live);
            return true;
        }
    };
    private final RewardMetricsEventListener listener = new RewardMetricsEventListener(fake,
            JsonMapper.builder().build());

    private static ConsumerRecord<String, String> rec(String type, String eventId, String payload) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>("t", 0, 0, "k", payload);
        r.headers().add("event-type", type.getBytes(StandardCharsets.UTF_8));
        if (eventId != null) {
            r.headers().add("event-id", eventId.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    @Test
    void deliveriesBookingsAndOffersAreRecorded() {
        String id = UUID.randomUUID().toString();
        assertThat(listener.handle(rec("orders.shipment.delivered", id, "{\"sellerOrgId\":\"org_1\",\"totalMinor\":"
                + "6400,\"currency\":\"USD\",\"deliveredAt\":\"2026-10-10T09:00:00Z\",\"extra\":true}"))).isTrue();
        assertThat(listener.handle(rec("booking.completed", UUID.randomUUID().toString(), "{\"orgId\":\"org_2\"}")))
                .isTrue();
        listener.handle(rec("offers.offer.created", UUID.randomUUID().toString(), "{\"offerId\":\"o1\","
                + "\"sellerOrgId\":\"org_1\",\"status\":\"ACTIVE\",\"productLive\":true}"));
        listener.handle(rec("offers.offer.updated", UUID.randomUUID().toString(), "{\"offerId\":\"o2\","
                + "\"sellerOrgId\":\"org_1\",\"status\":\"PAUSED\",\"productLive\":true}"));
        listener.handle(rec("offers.offer.withdrawn", UUID.randomUUID().toString(), "{\"offerId\":\"o1\","
                + "\"sellerOrgId\":\"org_1\",\"status\":\"ACTIVE\",\"productLive\":true}"));
        assertThat(calls).containsExactly("sale org_1 6400 USD 2026-10-10T09:00:00Z", "booking org_2",
                "offer o1 org_1 true", "offer o2 org_1 false", "offer o1 org_1 false");
    }

    @Test
    void otherEventsMissingIdsAndBadJsonAreSkipped() {
        assertThat(listener.handle(rec("orders.order.placed", UUID.randomUUID().toString(), "{}"))).isFalse();
        assertThat(listener.handle(rec("booking.completed", null, "{\"orgId\":\"org_2\"}"))).isFalse();
        assertThat(listener.handle(rec("booking.completed", "nope", "{\"orgId\":\"org_2\"}"))).isFalse();
        assertThat(listener.handle(rec("booking.completed", UUID.randomUUID().toString(), "{not json"))).isFalse();
        assertThat(listener.handle(rec("offers.buybox.changed", UUID.randomUUID().toString(), "{}"))).isFalse();
        assertThat(calls).isEmpty();
    }
}
