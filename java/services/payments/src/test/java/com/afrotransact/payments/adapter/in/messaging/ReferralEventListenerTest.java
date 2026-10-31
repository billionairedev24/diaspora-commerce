package com.afrotransact.payments.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.application.ReferralProgram;
import com.afrotransact.platform.events.consumer.NonRetryableEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReferralEventListenerTest {

    private final List<ReferralProgram.SignUp> signUps = new ArrayList<>();
    private final List<ReferralProgram.OrderPlaced> placed = new ArrayList<>();
    private final List<ReferralProgram.ShipmentEnded> ended = new ArrayList<>();
    private final ReferralEventListener listener = new ReferralEventListener(new ReferralProgram() {
        @Override
        public void signedUp(SignUp signUp) {
            signUps.add(signUp);
        }

        @Override
        public void orderPlaced(OrderPlaced order) {
            placed.add(order);
        }

        @Override
        public void shipmentEnded(ShipmentEnded shipment) {
            ended.add(shipment);
        }

        @Override
        public int grantDue(int batch) {
            return 0;
        }

        @Override
        public int expireStale(int batch) {
            return 0;
        }
    }, JsonMapper.builder().build());

    private static ConsumerRecord<String, String> rec(String topic, String type, String json) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>(topic, 0, 0L, "key", json);
        r.headers().add(EventHeaders.EVENT_TYPE, type.getBytes(StandardCharsets.UTF_8));
        r.headers().add(EventHeaders.EVENT_ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return r;
    }

    @Test
    void aRegistrationWithACodeBecomesASignUpWithItsSignals() {
        String hash = "a".repeat(64);
        listener.onIdentity(rec("at.identity.events", "identity.user.registered", "{\"userId\":\"u-1\","
                + "\"email\":\"ama@example.com\",\"firstName\":\"Ama\",\"lastName\":\"Mensah\",\"intent\":\"SHOP\","
                + "\"roles\":[\"account.customer\"],\"method\":\"PASSWORD\",\"emailVerified\":false,"
                + "\"smsConsent\":false,\"registeredAt\":\"2026-10-10T12:00:00Z\",\"referralCode\":\"KDM7-XR3Q\","
                + "\"deviceHash\":\"" + hash + "\",\"networkHash\":null,\"somethingNew\":1}"));
        assertThat(signUps).hasSize(1);
        ReferralProgram.SignUp s = signUps.getFirst();
        assertThat(s.userId()).isEqualTo("u-1");
        assertThat(s.firstName()).isEqualTo("Ama");
        assertThat(s.referralCode()).isEqualTo("KDM7-XR3Q");
        assertThat(s.deviceHash()).isEqualTo(hash);
        assertThat(s.networkHash()).isNull();
        assertThat(s.registeredAt()).isEqualTo(Instant.parse("2026-10-10T12:00:00Z"));
    }

    @Test
    void otherIdentityEventsAndMalformedMessagesAreIgnored() {
        listener.onIdentity(rec("at.identity.events", "identity.user.email-verified", "{\"userId\":\"u-1\"}"));
        assertThatThrownBy(() -> listener.onIdentity(rec("at.identity.events", "identity.user.registered", "not json")))
                .isInstanceOf(NonRetryableEventException.class);
        listener.onIdentity(rec("at.identity.events", "identity.user.registered", "{\"email\":\"x@example.com\"}"));
        assertThat(signUps).isEmpty();
    }

    @Test
    void orderPlacedCarriesTheAddressAndShipments() {
        listener.onOrder(rec("at.order.events", "orders.order.placed", "{\"orderId\":\"o-1\",\"customerId\":\"c-1\","
                + "\"totalMinor\":4500,\"placedAt\":\"2026-10-10T12:00:00Z\",\"shipToLine1\":\"12 Main St\","
                + "\"shipToPostalCode\":\"77002\",\"shipToCountry\":\"US\",\"shipments\":[{\"shipmentId\":\"s-1\","
                + "\"totalMinor\":3000,\"lines\":[]},{\"shipmentId\":\"s-2\",\"totalMinor\":1500}]}"));
        assertThat(placed).hasSize(1);
        ReferralProgram.OrderPlaced o = placed.getFirst();
        assertThat(o.orderId()).isEqualTo("o-1");
        assertThat(o.customerId()).isEqualTo("c-1");
        assertThat(o.totalMinor()).isEqualTo(4500);
        assertThat(o.shipToLine1()).isEqualTo("12 Main St");
        assertThat(o.shipments()).extracting(ReferralProgram.Shipment::shipmentId).containsExactly("s-1", "s-2");
    }

    @Test
    void deliveredAndCancelledShipmentsEndThem() {
        listener.onOrder(rec("at.order.events", "orders.shipment.delivered", "{\"orderId\":\"o-1\","
                + "\"customerId\":\"c-1\",\"shipmentId\":\"s-1\",\"deliveredAt\":\"2026-10-12T09:00:00Z\"}"));
        listener.onOrder(rec("at.order.events", "orders.shipment.cancelled", "{\"orderId\":\"o-1\","
                + "\"customerId\":\"c-1\",\"shipmentId\":\"s-2\",\"cancelledAt\":\"2026-10-11T09:00:00Z\"}"));
        listener.onOrder(rec("at.order.events", "orders.shipment.status_changed", "{\"shipmentId\":\"s-9\"}"));
        assertThat(ended).hasSize(2);
        assertThat(ended.get(0).delivered()).isTrue();
        assertThat(ended.get(0).at()).isEqualTo(Instant.parse("2026-10-12T09:00:00Z"));
        assertThat(ended.get(1).delivered()).isFalse();
        assertThat(ended.get(1).at()).isEqualTo(Instant.parse("2026-10-11T09:00:00Z"));
    }
}
