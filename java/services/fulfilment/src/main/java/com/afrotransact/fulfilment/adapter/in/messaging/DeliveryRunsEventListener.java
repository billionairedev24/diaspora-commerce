package com.afrotransact.fulfilment.adapter.in.messaging;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.fulfilment.application.RunDispatchService;
import com.afrotransact.fulfilment.domain.RunPickupPoint;
import com.afrotransact.fulfilment.port.DeliveryRunsClient.RunPlan;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code delivery-runs.run.confirmed} and {@code delivery-runs.run.cancelled} from {@code
 * at.delivery-runs.events} (key = run id; S264): a confirmed run is planned into routes, a cancelled one takes its
 * routes that have not started off the road. Other run events are ignored. Malformed messages are logged and skipped;
 * use-case failures propagate so the container retries the record (the inbox makes the retry safe).
 */
@Component
class DeliveryRunsEventListener {

    static final String RUN_CONFIRMED = "delivery-runs.run.confirmed";
    static final String RUN_CANCELLED = "delivery-runs.run.cancelled";

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryRunsEventListener.class);

    private final RunDispatchService dispatch;
    private final JsonMapper json;

    DeliveryRunsEventListener(RunDispatchService dispatch, JsonMapper json) {
        this.dispatch = dispatch;
        this.json = json;
    }

    @KafkaListener(id = "fulfilment-delivery-runs", groupId = "${spring.kafka.consumer.group-id:fulfilment-service}",
            topics = "${at.fulfilment.consumers.delivery-runs-topic:at.delivery-runs.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!RUN_CONFIRMED.equals(type) && !RUN_CANCELLED.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id header", type, rec.topic(),
                    rec.partition(), rec.offset());
            return;
        }
        try {
            handle(type, eventId.get(), json.readTree(rec.value()));
        } catch (JacksonException | IllegalArgumentException | DateTimeParseException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", type, eventId.get(), ex.getMessage());
        }
    }

    /** Dispatches one parsed event (package-private for tests). */
    void handle(String type, UUID eventId, JsonNode n) {
        String runId = text(n, "runId");
        if (runId == null) {
            LOG.warn("Skipping {} event {} without a runId", type, eventId);
            return;
        }
        if (RUN_CANCELLED.equals(type)) {
            dispatch.onRunCancelled(eventId, runId, text(n, "reason"));
            return;
        }
        Optional<RunPlan> plan = plan(n);
        if (plan.isEmpty()) {
            LOG.warn("Skipping {} event {} for run {}: no delivery window", type, eventId, runId);
            return;
        }
        dispatch.onRunConfirmed(eventId, plan.get());
    }

    /** The run as the confirmed event describes it (no hub: delivery-runs' dispatch list has it). */
    static Optional<RunPlan> plan(JsonNode n) {
        Instant start = instant(n, "windowStart");
        Instant end = instant(n, "windowEnd");
        if (start == null || end == null) {
            return Optional.empty();
        }
        List<String> orders = new ArrayList<>();
        for (JsonNode o : n.path("orders")) {
            if (text(o, "orderId") != null && !"CANCELLED".equals(text(o, "status"))) {
                orders.add(text(o, "orderId"));
            }
        }
        JsonNode p = n.path("pickupPoint");
        RunPickupPoint point = p.isObject() && text(p, "name") != null
                ? new RunPickupPoint(text(p, "name"), text(p, "line1"), text(p, "city"), text(p, "state"),
                text(p, "postalCode"), text(p, "hours"), text(p, "notes")) : null;
        String zone = text(n, "timeZone");
        return Optional.of(new RunPlan(text(n, "runId"), text(n, "status"), text(n, "town"), text(n, "state"),
                zone == null ? "America/Chicago" : zone, start, end, instant(n, "sellerPickupAt"),
                !"PICKUP_POINT".equals(text(n, "dropMode")), point, null, orders));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asString().isBlank() ? null : v.asString();
    }

    private static Instant instant(JsonNode n, String field) {
        String s = text(n, field);
        return s == null ? null : Instant.parse(s);
    }
}
