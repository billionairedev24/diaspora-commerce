package com.afrotransact.trustops.adapter.in.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 * Consumes catalog's product-review events ({@code at.product-review.events}, key = review id, so a review's events
 * stay in order): {@code catalog.review.submitted} (status PENDING: it failed the automatic checks, or was edited after
 * a rejection) and {@code catalog.review.reported} (a published review a customer or the seller reported) queue the
 * review for moderation as a PRODUCT_REVIEW item; {@code catalog.review.published | updated | rejected | removed |
 * deleted} close an open item when the review was decided, changed or withdrawn in catalog (a decision made here comes
 * back as the same event and is then a no-op). Every other event, such as the seller's reply, is ignored. The inbox
 * (consumer {@code trust-ops:product-reviews}) de-duplicates on {@code event-id}. Malformed messages are logged and
 * skipped; use-case failures propagate (retry).
 */
@Component
class CatalogReviewEventListener {

    static final String SUBMITTED = "catalog.review.submitted";
    static final String REPORTED = "catalog.review.reported";
    private static final Set<String> CLOSING = Set.of("catalog.review.published", "catalog.review.updated",
            "catalog.review.rejected", "catalog.review.removed", "catalog.review.deleted");

    private static final Logger LOG = LoggerFactory.getLogger(CatalogReviewEventListener.class);

    private final ModerationService moderation;
    private final JsonMapper json;

    CatalogReviewEventListener(ModerationService moderation, JsonMapper json) {
        this.moderation = moderation;
        this.json = json;
    }

    /** The CatalogReview fields moderation needs (contracts/asyncapi/fragments/catalog.yaml). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReviewMessage(UUID reviewId, UUID productId, String atin, String productTitle, String sellerOrgId,
                         String customerId, String reviewerName, Integer rating, String title, String text,
                         List<String> photoUrls, String status, List<String> flags, Instant createdAt,
                         Integer reportCount, String reportReason) {
    }

    @KafkaListener(id = "trust-ops-product-reviews",
            groupId = "${spring.kafka.consumer.group-id:trust-ops-service}-product-reviews",
            topics = "${at.trustops.consumers.product-review-topic:at.product-review.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.type(rec);
        boolean queue = SUBMITTED.equals(type) || REPORTED.equals(type);
        if (!queue && !CLOSING.contains(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id", type, rec.topic(), rec.partition(),
                    rec.offset());
            return;
        }
        ReviewMessage m;
        try {
            m = json.readValue(rec.value(), ReviewMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", type, eventId.get(), ex.getMessage());
            return;
        }
        if (m == null || m.reviewId() == null) {
            LOG.warn("Skipping {} event {} without reviewId", type, eventId.get());
            return;
        }
        if (!queue) {
            moderation.closeFromCatalogReview(eventId.get(), m.reviewId(), m.status() == null ? type : m.status());
            return;
        }
        boolean reported = REPORTED.equals(type);
        String expected = reported ? "PUBLISHED" : "PENDING";
        if (m.status() != null && !expected.equals(m.status())) {
            LOG.info("Not queueing review {} of event {}: it is {}, not {}", m.reviewId(), eventId.get(), m.status(),
                    expected);
            return;
        }
        ReviewSubmission review;
        try {
            review = ReviewSubmission.productReview(m.reviewId(), m.sellerOrgId(), m.productId(), m.atin(),
                    m.productTitle(), m.customerId(), m.reviewerName(), m.rating() == null ? 0 : m.rating(), m.title(),
                    m.text(), m.photoUrls(), m.flags(), reported ? Math.max(1, m.reportCount() == null ? 1
                            : m.reportCount()) : 0, reported ? m.reportReason() : null, m.createdAt());
        } catch (IllegalArgumentException | NullPointerException ex) {
            LOG.warn("Skipping {} event {} with an unusable review: {}", type, eventId.get(), ex.getMessage());
            return;
        }
        moderation.enqueueProductReview(eventId.get(), review);
    }
}
