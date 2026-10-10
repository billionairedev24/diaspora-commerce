package com.afrotransact.payments.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.application.LabelChargeNotice;
import com.afrotransact.payments.application.LabelChargeService;
import com.afrotransact.payments.domain.LabelCharge;
import com.afrotransact.payments.domain.LabelChargeStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CarrierShipmentEventListenerTest {

    private final List<LabelChargeNotice> purchased = new ArrayList<>();
    private final List<LabelChargeNotice> voided = new ArrayList<>();
    private final CarrierShipmentEventListener listener = new CarrierShipmentEventListener(new LabelChargeService() {
        @Override
        public boolean purchased(LabelChargeNotice notice) {
            purchased.add(notice);
            return true;
        }

        @Override
        public boolean voided(LabelChargeNotice notice) {
            voided.add(notice);
            return true;
        }

        @Override
        public List<LabelCharge> ofOrg(String orgId, LabelChargeStatus status, Instant from, Instant to,
                                       int limit) {
            return List.of();
        }
    }, JsonMapper.builder().build());

    static ConsumerRecord<String, String> rec(String type, String eventId, String json) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>("at.carrier-shipment.events", 0, 0L, "ups:1Z999", json);
        r.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        if (eventId != null) {
            r.headers().add(EventHeaders.EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
        }
        return r;
    }

    static String label(String labelId) {
        return "{\"labelId\":\"" + labelId + "\",\"orgId\":\"org_s\",\"shipmentId\":\"shp-1\",\"orderId\":\"ord-1\","
                + "\"reference\":\"shp-1\",\"originNode\":\"org_s\",\"carrier\":\"ups\",\"service\":\"03\","
                + "\"trackingNumber\":\"1Z999\",\"kind\":\"outbound\",\"fulfilment\":\"SELLER_SHIPPED\","
                + "\"billTo\":\"SELLER\",\"billingReason\":\"SELLER_SHIPPED\",\"costMinor\":999,\"handlingMinor\":125,"
                + "\"amountMinor\":1124,\"currency\":\"USD\",\"purchasedAt\":\"2026-10-10T12:00:00Z\"}";
    }

    @Test
    void labelPurchaseAndVoidBecomeNotices() {
        UUID id = UUID.randomUUID();
        listener.onMessage(rec("carrier.label.purchased", id.toString(), label("lbl-1")));
        assertThat(purchased).hasSize(1);
        LabelChargeNotice n = purchased.getFirst();
        assertThat(n.eventId()).isEqualTo(id);
        assertThat(n.labelId()).isEqualTo("lbl-1");
        assertThat(n.orgId()).isEqualTo("org_s");
        assertThat(n.shipmentId()).isEqualTo("shp-1");
        assertThat(n.trackingNumber()).isEqualTo("1Z999");
        assertThat(n.billTo()).isEqualTo("SELLER");
        assertThat(n.fulfilment()).isEqualTo("SELLER_SHIPPED");
        assertThat(n.costMinor()).isEqualTo(999);
        assertThat(n.handlingMinor()).isEqualTo(125);
        assertThat(n.currency()).isEqualTo("USD");
        assertThat(n.purchasedAt()).isEqualTo(Instant.parse("2026-10-10T12:00:00Z"));

        listener.onMessage(rec("carrier.label.voided", UUID.randomUUID().toString(), label("lbl-1")));
        assertThat(voided).extracting(LabelChargeNotice::labelId).containsExactly("lbl-1");
    }

    @Test
    void ignoresTrackingEventsAndUnusableMessages() {
        listener.onMessage(rec("carrier.tracking.updated", UUID.randomUUID().toString(), "{\"carrier\":\"ups\"}"));
        listener.onMessage(rec("carrier.label.purchased", null, label("lbl-1")));
        listener.onMessage(rec("carrier.label.purchased", "not-a-uuid", label("lbl-1")));
        listener.onMessage(rec("carrier.label.purchased", UUID.randomUUID().toString(), "{oops"));
        listener.onMessage(rec("carrier.label.purchased", UUID.randomUUID().toString(), "{\"orgId\":\"org_s\"}"));
        assertThat(purchased).isEmpty();
        assertThat(voided).isEmpty();
    }
}
