package com.afrotransact.orders.adapter.in.kafka;

import java.util.ArrayList;
import java.util.List;

import com.afrotransact.orders.application.PreorderCancellationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR 0005 §1: a cancelled pre-order window or delivery run refunds every order on it in full.
 * {@code preorder.window.cancelled} (window id, reason, decision) and {@code delivery-runs.run.cancelled}
 * ({@code orders[]}, reason) on {@code at.delivery-runs.events} cancel the orders' PREORDER shipments through the
 * ordinary cancellation path. No inbox is needed: cancelling is idempotent per shipment, so a redelivered event, or the
 * window event followed by the event of the run it was handed to, finds everything cancelled already. Failures
 * propagate so the container retries the record.
 */
@Component
class DeliveryRunsEventsListener {

    static final String WINDOW_CANCELLED = "preorder.window.cancelled";
    static final String RUN_CANCELLED = "delivery-runs.run.cancelled";

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryRunsEventsListener.class);

    private final PreorderCancellationService cancellations;
    private final JsonMapper json;

    DeliveryRunsEventsListener(PreorderCancellationService cancellations, JsonMapper json) {
        this.cancellations = cancellations;
        this.json = json;
    }

    @KafkaListener(id = "orders-delivery-runs-events",
            topics = "${at.orders.topics.delivery-runs-events:at.delivery-runs.events}",
            groupId = "${spring.application.name}", autoStartup = "${at.orders.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> record) {
        handle(record);
    }

    /** Parses and applies one record (visible for tests); null when the event is not one we act on. */
    PreorderCancellationService.Result handle(ConsumerRecord<String, String> record) {
        EventHeaders headers = EventHeaders.of(record);
        if (WINDOW_CANCELLED.equals(headers.type())) {
            JsonNode p = json.readTree(record.value());
            String windowId = p.path("windowId").asString("");
            if (windowId.isEmpty()) {
                LOG.warn("{} {} without windowId; skipped", WINDOW_CANCELLED, headers.id());
                return null;
            }
            return cancellations.cancelWindow(windowId, reason(p, "Pre-order window cancelled"));
        }
        if (RUN_CANCELLED.equals(headers.type())) {
            JsonNode p = json.readTree(record.value());
            List<String> orderIds = new ArrayList<>();
            for (JsonNode o : p.path("orders")) {
                String id = o.path("orderId").asString("");
                if (!id.isEmpty()) {
                    orderIds.add(id);
                }
            }
            if (orderIds.isEmpty()) {
                return null;
            }
            return cancellations.cancelOrders(orderIds, reason(p, "Delivery run cancelled"));
        }
        return null;
    }

    /** The staff reason (window: {@code decision.reason}, run: {@code reason}), else a default; at most 500 chars. */
    private static String reason(JsonNode p, String fallback) {
        String reason = p.path("decision").path("reason").asString("");
        if (reason.isBlank()) {
            reason = p.path("reason").asString("");
        }
        String text = reason.isBlank() ? fallback : fallback + ": " + reason.trim();
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
