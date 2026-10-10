package com.afrotransact.taxreturns.adapter.in.messaging;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.application.CarrierScan;
import com.afrotransact.taxreturns.application.ReturnService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code carrier.tracking.updated} from {@code at.carrier-shipment.events}: scans of return labels move RMAs
 * to in transit (first scan, refund-at-first-scan) and received. Scans of other parcels are ignored.
 */
@Component
class CarrierEventsListener {

    static final String TRACKING_UPDATED = "carrier.tracking.updated";

    private static final Logger LOG = LoggerFactory.getLogger(CarrierEventsListener.class);

    private final ReturnService returns;
    private final JsonMapper json;

    CarrierEventsListener(ReturnService returns, JsonMapper json) {
        this.returns = returns;
        this.json = json;
    }

    @KafkaListener(id = "taxreturns-carrier", groupId = "${spring.kafka.consumer.group-id:tax-returns-service}",
            topics = "${at.taxreturns.consumers.carrier-topic:at.carrier-shipment.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        if (!TRACKING_UPDATED.equals(EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse(""))) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            return;
        }
        JsonNode n;
        try {
            n = json.readTree(rec.value());
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed carrier.tracking.updated {}: {}", eventId.get(), ex.getMessage());
            return;
        }
        String reference = Json.text(n, "reference");
        String tracking = Json.text(n, "trackingNumber");
        if (tracking == null && (reference == null || !reference.startsWith("RMA-"))) {
            return;
        }
        returns.onCarrierScan(eventId.get(), new CarrierScan(Json.text(n, "carrier"), tracking, reference,
                Json.text(n, "state"), Json.instant(n.path("event"), "occurredAt")));
    }
}
