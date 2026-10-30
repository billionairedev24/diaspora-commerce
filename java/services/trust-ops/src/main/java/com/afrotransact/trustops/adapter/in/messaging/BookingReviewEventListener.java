package com.afrotransact.trustops.adapter.in.messaging;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.afrotransact.trustops.application.ModerationService;
import com.afrotransact.trustops.domain.ReviewSubmission;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes bookings-service's review events ({@code at.bookings.events}, key = booking id, so a review's events stay
 * in order): {@code booking.review.submitted} (status PENDING) queues the review for moderation as a SERVICE_REVIEW
 * item; {@code booking.review.approved|rejected} close an open item when the decision was made in bookings (a
 * decision made here comes back as the same event and is then a no-op). Every other bookings event is ignored. The
 * inbox (consumer {@code trust-ops:booking-reviews}) de-duplicates on {@code event-id}. Malformed messages are
 * logged and skipped; use-case failures propagate (retry).
 */
@Component
class BookingReviewEventListener {

    static final String SUBMITTED = "booking.review.submitted";
    static final String APPROVED = "booking.review.approved";
    static final String REJECTED = "booking.review.rejected";
    private static final Set<String> HANDLED = Set.of(SUBMITTED, APPROVED, REJECTED);

    private static final Logger LOG = LoggerFactory.getLogger(BookingReviewEventListener.class);

    private final ModerationService moderation;
    private final JsonMapper json;

    BookingReviewEventListener(ModerationService moderation, JsonMapper json) {
        this.moderation = moderation;
        this.json = json;
    }

    /** The BookingsReviewEvent fields moderation needs (contracts/asyncapi/fragments/bookings.yaml). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReviewMessage(UUID bookingId, String orgId, UUID serviceId, String serviceName, String customerId,
                         String reviewerName, Integer rating, String text, String status, Instant createdAt) {
    }

    @KafkaListener(id = "trust-ops-booking-reviews",
            groupId = "${spring.kafka.consumer.group-id:trust-ops-service}-reviews",
            topics = "${at.trustops.consumers.bookings-topic:at.bookings.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.type(rec);
        if (!HANDLED.contains(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            throw MalformedEvent.missingEventId(type, rec);
        }
        ReviewMessage m;
        try {
            m = json.readValue(rec.value(), ReviewMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null || m.bookingId() == null) {
            LOG.warn("Skipping {} event {} without bookingId", type, eventId.get());
            return;
        }
        if (!SUBMITTED.equals(type)) {
            moderation.closeFromBookings(eventId.get(), m.bookingId(), m.status() == null ? type : m.status());
            return;
        }
        if (m.status() != null && !"PENDING".equals(m.status())) {
            LOG.info("Not queueing review {} of event {}: already {}", m.bookingId(), eventId.get(), m.status());
            return;
        }
        ReviewSubmission review;
        try {
            review = new ReviewSubmission(m.bookingId(), m.orgId(), m.serviceId(), m.serviceName(), m.customerId(),
                    m.reviewerName(), m.rating() == null ? 0 : m.rating(), m.text(), m.createdAt());
        } catch (IllegalArgumentException ex) {
            LOG.warn("Skipping {} event {} with an unusable review: {}", type, eventId.get(), ex.getMessage());
            return;
        }
        moderation.enqueueReview(eventId.get(), review);
    }
}
