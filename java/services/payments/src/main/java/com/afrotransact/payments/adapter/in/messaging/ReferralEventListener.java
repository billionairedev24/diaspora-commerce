package com.afrotransact.payments.adapter.in.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.application.ReferralProgram;
import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feeds the referral program (S322): {@code identity.user.registered} from {@code at.identity.events} (a friend signs up
 * with a code; the sign-up device and network are kept for the abuse checks) and {@code orders.order.placed},
 * {@code orders.shipment.delivered} and {@code orders.shipment.cancelled} from {@code at.order.events} (a referred
 * friend's first order, its delivery). Other events on those topics are ignored. Malformed messages are logged and
 * skipped; use-case failures propagate so the container retries the record (every handler is idempotent).
 */
@Component
class ReferralEventListener {

    static final String USER_REGISTERED = "identity.user.registered";
    static final String ORDER_PLACED = "orders.order.placed";
    static final String SHIPMENT_DELIVERED = "orders.shipment.delivered";
    static final String SHIPMENT_CANCELLED = "orders.shipment.cancelled";


    private final ReferralProgram program;
    private final JsonMapper json;

    ReferralEventListener(ReferralProgram program, JsonMapper json) {
        this.program = program;
        this.json = json;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Registered(String userId, String firstName, String lastName, String intent, String referralCode,
                      String deviceHash, String networkHash, Instant registeredAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Placed(String orderId, String customerId, Long totalMinor, Instant placedAt, String shipToLine1,
                  String shipToLine2, String shipToPostalCode, String shipToCountry, List<PlacedShipment> shipments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlacedShipment(String shipmentId, Long totalMinor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ended(String orderId, String customerId, String shipmentId, Instant deliveredAt, Instant cancelledAt) {
    }

    @KafkaListener(id = "payments-referral-identity-events",
            groupId = "${spring.kafka.consumer.group-id:payments-service}-referral",
            topics = "${at.payments.consumers.identity-topic:at.identity.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onIdentity(ConsumerRecord<String, String> rec) {
        if (!USER_REGISTERED.equals(EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse(""))) {
            return;
        }
        Registered m = read(rec, Registered.class);
        if (m == null || m.userId() == null) {
            return;
        }
        program.signedUp(new ReferralProgram.SignUp(m.userId(), m.firstName(), m.lastName(), m.intent(),
                m.referralCode(), m.deviceHash(), m.networkHash(), m.registeredAt()));
    }

    @KafkaListener(id = "payments-referral-order-events",
            groupId = "${spring.kafka.consumer.group-id:payments-service}-referral",
            topics = "${at.payments.consumers.order-topic:at.order.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onOrder(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        switch (type) {
            case ORDER_PLACED -> placed(rec);
            case SHIPMENT_DELIVERED -> ended(rec, true);
            case SHIPMENT_CANCELLED -> ended(rec, false);
            default -> {
                // not for the referral program
            }
        }
    }

    private void placed(ConsumerRecord<String, String> rec) {
        Placed m = read(rec, Placed.class);
        if (m == null || m.orderId() == null || m.customerId() == null) {
            return;
        }
        List<PlacedShipment> shipments = m.shipments() == null ? List.of() : m.shipments();
        program.orderPlaced(new ReferralProgram.OrderPlaced(m.orderId(), m.customerId(),
                m.totalMinor() == null ? 0 : m.totalMinor(), m.placedAt(), m.shipToLine1(), m.shipToLine2(),
                m.shipToPostalCode(), m.shipToCountry(), shipments.stream()
                        .filter(s -> s.shipmentId() != null)
                        .map(s -> new ReferralProgram.Shipment(s.shipmentId(),
                                s.totalMinor() == null ? 0 : s.totalMinor())).toList()));
    }

    private void ended(ConsumerRecord<String, String> rec, boolean delivered) {
        Ended m = read(rec, Ended.class);
        if (m == null || m.shipmentId() == null) {
            return;
        }
        program.shipmentEnded(new ReferralProgram.ShipmentEnded(m.shipmentId(), m.orderId(), m.customerId(),
                delivered, delivered ? m.deliveredAt() : m.cancelledAt()));
    }

    private <T> T read(ConsumerRecord<String, String> rec, Class<T> type) {
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        try {
            return json.readValue(rec.value(), type);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of("referral", ex);
        }
    }
}
