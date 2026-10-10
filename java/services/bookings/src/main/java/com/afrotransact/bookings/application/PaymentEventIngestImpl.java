package com.afrotransact.bookings.application;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.port.BookingRepository;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PaymentEventIngestImpl implements PaymentEventIngest {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentEventIngestImpl.class);
    private static final String INTENT_SUCCEEDED = "payments.intent.succeeded";
    private static final String INTENT_FAILED = "payments.intent.failed";

    private final InboxDeduplicator inbox;
    private final BookingRepository bookings;
    private final PaymentOutcomes outcomes;

    PaymentEventIngestImpl(InboxDeduplicator inbox, BookingRepository bookings, PaymentOutcomes outcomes) {
        this.inbox = inbox;
        this.bookings = bookings;
        this.outcomes = outcomes;
    }

    @Override
    @Transactional
    public boolean handle(UUID eventId, String eventType, String orderId, String paymentStatus,
                          String failureMessage) {
        if (orderId == null || !orderId.startsWith("BK-")) {
            return false;
        }
        if (!INTENT_SUCCEEDED.equals(eventType) && !INTENT_FAILED.equals(eventType)) {
            return false;
        }
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            LOG.debug("Duplicate payment event {} ignored", eventId);
            return false;
        }
        Optional<Booking> booking = bookings.findByReference(orderId);
        if (booking.isEmpty()) {
            LOG.warn("Payment event {} for unknown booking {}", eventId, orderId);
            return false;
        }
        String status = INTENT_FAILED.equals(eventType) ? "FAILED" : paymentStatus;
        Booking before = booking.get();
        Booking after = outcomes.apply(before.id(), status, failureMessage);
        return after.status() != before.status() || after.paymentState() != before.paymentState();
    }
}
