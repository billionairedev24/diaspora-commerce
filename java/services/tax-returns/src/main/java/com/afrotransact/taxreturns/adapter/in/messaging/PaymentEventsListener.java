package com.afrotransact.taxreturns.adapter.in.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.application.PaymentRefunded;
import com.afrotransact.taxreturns.application.TaxLedgerService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code payments.refunded} from {@code at.payment.events} so every refund (returns, order cancellations,
 * staff refunds) reverses its tax in the ledger, keyed by the payments refund id.
 */
@Component
class PaymentEventsListener {

    static final String REFUNDED = "payments.refunded";

    private static final Logger LOG = LoggerFactory.getLogger(PaymentEventsListener.class);

    private final TaxLedgerService ledger;
    private final JsonMapper json;

    PaymentEventsListener(TaxLedgerService ledger, JsonMapper json) {
        this.ledger = ledger;
        this.json = json;
    }

    @KafkaListener(id = "taxreturns-payments", groupId = "${spring.kafka.consumer.group-id:tax-returns-service}",
            topics = "${at.taxreturns.consumers.payment-topic:at.payment.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        if (!REFUNDED.equals(EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse(""))) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping payments.refunded at {}-{}@{}: missing event-id", rec.topic(), rec.partition(),
                    rec.offset());
            return;
        }
        JsonNode n;
        try {
            n = json.readTree(rec.value());
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed payments.refunded {}: {}", eventId.get(), ex.getMessage());
            return;
        }
        String refundId = Json.text(n, "refundId");
        if (refundId == null || Json.text(n, "orderId") == null) {
            LOG.warn("Skipping payments.refunded {}: refundId / orderId missing", eventId.get());
            return;
        }
        List<PaymentRefunded.Line> lines = new ArrayList<>();
        for (JsonNode l : n.path("lines")) {
            if (Json.text(l, "lineId") != null) {
                lines.add(new PaymentRefunded.Line(Json.text(l, "lineId"), l.path("itemsMinor").asLong(),
                        l.path("taxMinor").asLong()));
            }
        }
        String occurred = EventHeaders.text(rec, "occurred-at").orElse(null);
        ledger.onPaymentRefunded(eventId.get(), new PaymentRefunded(Json.text(n, "orderId"), refundId,
                Json.text(n, "currency"), Json.text(n, "status"), Json.parse(occurred), lines));
    }
}
