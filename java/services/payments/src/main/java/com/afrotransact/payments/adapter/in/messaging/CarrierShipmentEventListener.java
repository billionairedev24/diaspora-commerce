package com.afrotransact.payments.adapter.in.messaging;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.application.LabelChargeNotice;
import com.afrotransact.payments.application.LabelChargeService;
import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code carrier.label.purchased} / {@code carrier.label.voided} from {@code at.carrier-shipment.events}
 * (key = carrier:trackingNumber, so a label's purchase and void stay in order): labels billed to a seller are charged
 * to (and credited back to) its payable. Tracking and pickup events on the same topic are ignored. Malformed messages
 * are logged and skipped; use-case failures propagate so the container retries the record (the inbox makes the retry
 * safe).
 */
@Component
class CarrierShipmentEventListener {

    static final String LABEL_PURCHASED = "carrier.label.purchased";
    static final String LABEL_VOIDED = "carrier.label.voided";

    private static final Logger LOG = LoggerFactory.getLogger(CarrierShipmentEventListener.class);

    private final LabelChargeService labels;
    private final JsonMapper json;

    CarrierShipmentEventListener(LabelChargeService labels, JsonMapper json) {
        this.labels = labels;
        this.json = json;
    }

    /** The fields payments needs from carrier-gateway's CarrierLabelEvent (carrier-gateway.yaml). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LabelMessage(String labelId, String orgId, String shipmentId, String orderId, String carrier,
                        String service, String trackingNumber, String kind, String fulfilment, String billTo,
                        Long costMinor, Long handlingMinor, String currency, Instant purchasedAt) {
    }

    @KafkaListener(id = "payments-carrier-shipment-events",
            groupId = "${spring.kafka.consumer.group-id:payments-service}",
            topics = "${at.payments.consumers.carrier-shipment-topic:at.carrier-shipment.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!LABEL_PURCHASED.equals(type) && !LABEL_VOIDED.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            throw MalformedEvent.missingEventId(type, rec);
        }
        LabelMessage m;
        try {
            m = json.readValue(rec.value(), LabelMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null || m.labelId() == null || m.labelId().isBlank()) {
            LOG.warn("Skipping {} event {}: no label id", type, eventId.get());
            return;
        }
        LabelChargeNotice notice = new LabelChargeNotice(eventId.get(), m.labelId(), m.orgId(), m.shipmentId(),
                m.orderId(), m.carrier(), m.service(), m.trackingNumber(), m.kind(), m.fulfilment(), m.billTo(),
                m.costMinor() == null ? 0 : m.costMinor(), m.handlingMinor() == null ? 0 : m.handlingMinor(),
                m.currency(), m.purchasedAt());
        if (LABEL_PURCHASED.equals(type)) {
            labels.purchased(notice);
        } else {
            labels.voided(notice);
        }
    }
}
