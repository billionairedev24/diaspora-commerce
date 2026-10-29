package com.afrotransact.deliveryruns.adapter.in.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.RunMoneyService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code payments.captured} (the seller recipients' commission, the platform's fees on the goods) and
 * {@code payments.refunded} (SUCCEEDED refunds: the amount and its delivery-fee part from {@code lines[].shippingMinor})
 * from {@code at.payment.events} into the run slot of the order (S265). Malformed messages are logged and skipped.
 */
@Component
class PaymentEventsListener {

    static final String PAYMENTS_CAPTURED = "payments.captured";
    static final String PAYMENTS_REFUNDED = "payments.refunded";
    static final String SELLER = "SELLER";

    private static final Logger LOG = LoggerFactory.getLogger(PaymentEventsListener.class);

    private final RunMoneyService money;
    private final JsonMapper json;

    PaymentEventsListener(RunMoneyService money, JsonMapper json) {
        this.money = money;
        this.json = json;
    }

    @KafkaListener(id = "delivery-runs-payments", groupId = "${spring.kafka.consumer.group-id:delivery-runs-service}",
            topics = "${at.delivery-runs.consumers.payment-topic:at.payment.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = header(rec, "event-type").orElse("");
        if (!PAYMENTS_CAPTURED.equals(type) && !PAYMENTS_REFUNDED.equals(type)) {
            return;
        }
        JsonNode n;
        try {
            n = json.readTree(rec.value());
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed {} at {}-{}@{}: {}", type, rec.topic(), rec.partition(), rec.offset(),
                    ex.getMessage());
            return;
        }
        handle(type, n, instant(n, "occurredAt"));
    }

    /** Dispatches one parsed event (package-private for tests). */
    void handle(String type, JsonNode n, Instant at) {
        String orderId = text(n, "orderId");
        if (orderId == null) {
            LOG.warn("Skipping {}: orderId missing", type);
            return;
        }
        if (PAYMENTS_CAPTURED.equals(type)) {
            Optional<UUID> capture = uuid(text(n, "captureId"));
            if (capture.isEmpty()) {
                LOG.warn("Skipping {} of order {}: captureId missing", type, orderId);
                return;
            }
            long commission = 0;
            for (JsonNode r : n.path("recipients")) {
                if (SELLER.equalsIgnoreCase(text(r, "kind"))) {
                    commission += r.path("commissionMinor").asLong(0);
                }
            }
            money.onCaptured(capture.get(), orderId, commission, at);
        } else if ("SUCCEEDED".equalsIgnoreCase(text(n, "status"))) {
            Optional<UUID> refund = uuid(text(n, "refundId"));
            if (refund.isEmpty()) {
                LOG.warn("Skipping {} of order {}: refundId missing", type, orderId);
                return;
            }
            long shipping = 0;
            for (JsonNode l : n.path("lines")) {
                shipping += l.path("shippingMinor").asLong(0);
            }
            money.onRefunded(refund.get(), orderId, n.path("amountMinor").asLong(0), shipping, at);
        }
    }

    private static Optional<String> header(ConsumerRecord<?, ?> rec, String name) {
        Header h = rec.headers().lastHeader(name);
        return h == null || h.value() == null ? Optional.empty()
                : Optional.of(new String(h.value(), StandardCharsets.UTF_8));
    }

    private static Optional<UUID> uuid(String s) {
        try {
            return s == null ? Optional.empty() : Optional.of(UUID.fromString(s));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asString().isBlank() ? null : v.asString();
    }

    private static Instant instant(JsonNode n, String field) {
        String s = text(n, field);
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
