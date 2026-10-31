package com.afrotransact.payments.adapter.in.messaging;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.application.SupplierPaymentNotice;
import com.afrotransact.payments.application.SupplierPaymentService;
import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code retail.payment.released} from {@code at.retail.events} (retail-service, key = PO id): AfroTransact
 * Retail's supplier invoices passed the three-way match, so payments books the payable and schedules the supplier
 * payment (S226). Every other retail event is ignored. Malformed messages are logged and skipped; use-case failures
 * propagate so the container retries the record (the inbox makes the retry safe).
 */
@Component
class RetailEventListener {

    static final String PAYMENT_RELEASED = "retail.payment.released";


    private final SupplierPaymentService supplierPayments;
    private final JsonMapper json;

    RetailEventListener(SupplierPaymentService supplierPayments, JsonMapper json) {
        this.supplierPayments = supplierPayments;
        this.json = json;
    }

    /** The fields payments needs from retail-service's RetailPaymentReleased (retail.yaml). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentReleasedMessage(UUID poId, String poNumber, UUID supplierId, String supplierCode, String currency,
                                  Long amountMinor, List<InvoiceMessage> invoices, String dueOn) {
    }

    /** One released invoice. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record InvoiceMessage(UUID invoiceId, String invoiceNumber, String currency, Long amountMinor) {
    }

    @KafkaListener(id = "payments-retail-events",
            groupId = "${spring.kafka.consumer.group-id:payments-service}",
            topics = "${at.payments.consumers.retail-topic:at.retail.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!PAYMENT_RELEASED.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            throw MalformedEvent.missingEventId(type, rec);
        }
        PaymentReleasedMessage m;
        try {
            m = json.readValue(rec.value(), PaymentReleasedMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null) {
            return;
        }
        List<SupplierPaymentNotice.Invoice> invoices = m.invoices() == null ? List.of() : m.invoices().stream()
                .map(i -> new SupplierPaymentNotice.Invoice(i.invoiceId(), i.invoiceNumber(), i.currency(),
                        i.amountMinor() == null ? 0 : i.amountMinor())).toList();
        supplierPayments.released(new SupplierPaymentNotice(eventId.get(), m.poId(), m.poNumber(), m.supplierId(),
                m.supplierCode(), m.currency(), m.amountMinor() == null ? 0 : m.amountMinor(), invoices,
                occurredAt(rec), dueOn(m.dueOn())));
    }

    private static Instant occurredAt(ConsumerRecord<String, String> rec) {
        return EventHeaders.text(rec, EventHeaders.OCCURRED_AT).flatMap(s -> {
            try {
                return Optional.of(Instant.parse(s));
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    private static LocalDate dueOn(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
