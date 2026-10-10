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
    private static final String INTENT_ACTION_REQUIRED = "payments.intent.action_required";

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
        return apply(eventId, eventType, new IntentEvent(orderId, null, paymentStatus, failureMessage, null, false));
    }

    @Override
    @Transactional
    public boolean handle(UUID eventId, String eventType, IntentEvent event) {
        return apply(eventId, eventType, event);
    }

    private boolean apply(UUID eventId, String eventType, IntentEvent event) {
        String orderId = event.orderId();
        if (orderId == null || !orderId.startsWith("BK-")) {
            return false;
        }
        boolean actionRequired = INTENT_ACTION_REQUIRED.equals(eventType);
        if (!INTENT_SUCCEEDED.equals(eventType) && !INTENT_FAILED.equals(eventType)
                && !(actionRequired && event.balanceCharge())) {
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
        Booking before = booking.get();
        String status = INTENT_FAILED.equals(eventType) ? "FAILED"
                : actionRequired ? "REQUIRES_ACTION" : event.status();
        if (event.balanceCharge()) {
            if (event.parentPaymentId() != null && !event.parentPaymentId().equals(before.paymentId())) {
                LOG.warn("Balance charge event {} names parent {} but booking {} has payment {}", eventId,
                        event.parentPaymentId(), orderId, before.paymentId());
                return false;
            }
            Booking after = outcomes.applyBalance(before.id(), event.paymentId(), status, null,
                    event.failureMessage());
            return after.balance().state() != before.balance().state() || after.version() != before.version();
        }
        Booking after = outcomes.apply(before.id(), status, event.failureMessage());
        return after.status() != before.status() || after.paymentState() != before.paymentState();
    }
}
