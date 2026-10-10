package com.afrotransact.orders.adapter.in.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.orders.application.CarrierEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * carrier.tracking.updated on at.carrier-shipment.events → shipment transitions (actor CARRIER); carrier.pickup.ready
 * on the same topic → pickup shipments ready for collection (actor PICKUP_POINT, S219).
 */
@Component
class CarrierEventsListener {

    static final String TRACKING_UPDATED = "carrier.tracking.updated";
    static final String PICKUP_READY = "carrier.pickup.ready";

    private static final Logger LOG = LoggerFactory.getLogger(CarrierEventsListener.class);

    private final CarrierEventService carriers;
    private final JsonMapper json;

    CarrierEventsListener(CarrierEventService carriers, JsonMapper json) {
        this.carriers = carriers;
        this.json = json;
    }

    @KafkaListener(id = "orders-carrier-events",
            topics = "${at.orders.topics.carrier-events:at.carrier-shipment.events}",
            groupId = "${spring.application.name}", autoStartup = "${at.orders.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> record) {
        handle(record);
    }

    /** Parses and applies one record (visible for tests). */
    CarrierEventService.Result handle(ConsumerRecord<String, String> record) {
        EventHeaders headers = EventHeaders.of(record);
        if (PICKUP_READY.equals(headers.type())) {
            return pickupReady(record, headers);
        }
        if (!TRACKING_UPDATED.equals(headers.type())) {
            return CarrierEventService.Result.IGNORED;
        }
        JsonNode p = json.readTree(record.value());
        JsonNode event = p.path("event");
        String occurred = event.path("occurredAt").asString("");
        CarrierEventService.Scan scan = new CarrierEventService.Scan(p.path("carrier").asString(null),
                p.path("trackingNumber").asString(null), p.path("reference").asString(null),
                p.path("state").asString(null), event.path("eventId").asString(null),
                occurred.isEmpty() ? null : Instant.parse(occurred), event.path("description").asString(null),
                event.path("location").asString(null));
        CarrierEventService.Result result = carriers.apply(eventId(record, headers), scan);
        LOG.debug("carrier event {} {} {} → {}", scan.carrier(), scan.trackingNumber(), scan.state(), result);
        return result;
    }

    private CarrierEventService.Result pickupReady(ConsumerRecord<String, String> record, EventHeaders headers) {
        JsonNode p = json.readTree(record.value());
        String ready = p.path("readyAt").asString("");
        CarrierEventService.PickupReady notice = new CarrierEventService.PickupReady(
                p.path("locationId").asString(null), p.path("name").asString(null),
                p.path("orderRef").asString(null), p.path("customerRef").asString(null),
                ready.isEmpty() ? null : Instant.parse(ready));
        CarrierEventService.Result result = carriers.pickupReady(eventId(record, headers), notice);
        LOG.debug("pickup ready {} at {} → {}", notice.orderRef(), notice.locationId(), result);
        return result;
    }

    private static UUID eventId(ConsumerRecord<String, String> record, EventHeaders headers) {
        return headers.id() != null ? headers.id() : UUID.nameUUIDFromBytes((record.topic() + ":"
                + record.partition() + ":" + record.offset()).getBytes(StandardCharsets.UTF_8));
    }
}
