package com.afrotransact.payments.adapter.in.messaging;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.application.ProviderFeeNotice;
import com.afrotransact.payments.application.ProviderFeeService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code booking.cancelled} from {@code at.bookings.events} (key = booking id): a cancellation by the
 * provider that carries a late-cancellation fee ({@code cancelledBy = PROVIDER}, {@code providerFeeMinor > 0}) is
 * debited from the provider. Every other booking event is ignored. Malformed messages are logged and skipped;
 * use-case failures propagate so the container retries the record (the inbox makes the retry safe).
 */
@Component
class BookingsEventListener {

    static final String BOOKING_CANCELLED = "booking.cancelled";
    static final String PROVIDER = "PROVIDER";

    private static final Logger LOG = LoggerFactory.getLogger(BookingsEventListener.class);

    private final ProviderFeeService fees;
    private final JsonMapper json;

    BookingsEventListener(ProviderFeeService fees, JsonMapper json) {
        this.fees = fees;
        this.json = json;
    }

    /** The fields payments needs from bookings' BookingsBookingEvent (bookings.yaml). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BookingMessage(String bookingId, String bookingReference, String orgId, String status, String currency,
                          Long providerFeeMinor, String cancelledBy) {
    }

    @KafkaListener(id = "payments-bookings-events", groupId = "${spring.kafka.consumer.group-id:payments-service}",
            topics = "${at.payments.consumers.bookings-topic:at.bookings.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!BOOKING_CANCELLED.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id header", type, rec.topic(),
                    rec.partition(), rec.offset());
            return;
        }
        BookingMessage m;
        try {
            m = json.readValue(rec.value(), BookingMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", type, eventId.get(), ex.getMessage());
            return;
        }
        if (m == null || !PROVIDER.equals(m.cancelledBy()) || m.providerFeeMinor() == null
                || m.providerFeeMinor() <= 0) {
            return;
        }
        Instant occurredAt = EventHeaders.text(rec, EventHeaders.OCCURRED_AT).flatMap(BookingsEventListener::instant)
                .orElse(null);
        fees.lateCancellation(new ProviderFeeNotice(eventId.get(), m.bookingId(), m.bookingReference(), m.orgId(),
                m.providerFeeMinor(), m.currency(), occurredAt));
    }

    private static Optional<Instant> instant(String text) {
        try {
            return Optional.of(Instant.parse(text));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
