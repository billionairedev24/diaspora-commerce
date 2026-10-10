package com.afrotransact.orders.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.orders.application.CarrierEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EventListenersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ConsumerRecord<String, String> record(String topic, UUID eventId, String type, String payload) {
        ConsumerRecord<String, String> r = new ConsumerRecord<>(topic, 0, 42L, "key", payload);
        r.headers().add(new RecordHeader("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8)));
        r.headers().add(new RecordHeader("event-type", type.getBytes(StandardCharsets.UTF_8)));
        return r;
    }

    @Test
    void carrierTrackingUpdatesAreParsedFromThePlatformEnvelope() {
        List<CarrierEventService.Scan> seen = new ArrayList<>();
        List<CarrierEventService.PickupReady> ready = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        CarrierEventsListener listener = new CarrierEventsListener(new CarrierEventService() {
            @Override
            public Result apply(UUID id, Scan scan) {
                ids.add(id);
                seen.add(scan);
                return Result.APPLIED;
            }

            @Override
            public Result pickupReady(UUID id, PickupReady notice) {
                ids.add(id);
                ready.add(notice);
                return Result.APPLIED;
            }
        }, JSON);
        UUID eventId = UUID.randomUUID();
        String payload = """
                {"carrier":"ups","trackingNumber":"1Z999","labelId":null,"reference":"ship-1","state":"in_transit",
                 "previousState":"picked_up","stateChanged":true,
                 "event":{"eventId":"ups-77","code":"I","description":"Departed facility","state":"in_transit",
                          "occurredAt":"2026-10-01T12:30:00Z","location":"Houston, TX","sequence":3}}
                """;

        assertThat(listener.handle(record("at.carrier-shipment.events", eventId, "carrier.tracking.updated",
                payload))).isEqualTo(CarrierEventService.Result.APPLIED);
        assertThat(ids).containsExactly(eventId);
        assertThat(seen.getFirst()).isEqualTo(new CarrierEventService.Scan("ups", "1Z999", "ship-1", "in_transit",
                "ups-77", Instant.parse("2026-10-01T12:30:00Z"), "Departed facility", "Houston, TX"));

        // S219: carrier.pickup.ready (same topic) goes to the pickup-ready use case.
        UUID readyId = UUID.randomUUID();
        assertThat(listener.handle(record("at.carrier-shipment.events", readyId, "carrier.pickup.ready", """
                {"locationId":"7f1c9b1e-4a1d-4f43-9a52-0f7e2b0e4c11","name":"Houston counter","kind":"PLATFORM_COUNTER",
                 "orderRef":"AT-2026-ABC123","customerRef":"cus-1","pickupCode":null,"readyAt":"2026-10-01T15:00:00Z",
                 "hours":[]}
                """))).isEqualTo(CarrierEventService.Result.APPLIED);
        assertThat(ready).containsExactly(new CarrierEventService.PickupReady("7f1c9b1e-4a1d-4f43-9a52-0f7e2b0e4c11",
                "Houston counter", "AT-2026-ABC123", "cus-1", Instant.parse("2026-10-01T15:00:00Z")));
        assertThat(ids).containsExactly(eventId, readyId);
        assertThat(listener.handle(record("at.carrier-shipment.events", UUID.randomUUID(), "carrier.label.voided",
                "{}"))).isEqualTo(CarrierEventService.Result.IGNORED);
        assertThat(seen).hasSize(1);
    }

    @Test
    void paymentIntentEventsSignalTheSaga() {
        List<String> calls = new ArrayList<>();
        PaymentEventsListener listener = new PaymentEventsListener((id, type, orderId, failure) -> {
            calls.add(type + "|" + orderId + "|" + failure);
            return true;
        }, JSON);
        listener.onMessage(record("at.payment.events", UUID.randomUUID(), "payments.intent.failed",
                "{\"paymentId\":\"p\",\"orderId\":\"o-1\",\"status\":\"FAILED\",\"failureMessage\":\"declined\"}"));
        listener.onMessage(record("at.payment.events", UUID.randomUUID(), "payments.captured",
                "{\"orderId\":\"o-1\"}"));
        assertThat(calls).containsExactly("payments.intent.failed|o-1|declined");
    }
}
