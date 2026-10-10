package com.afrotransact.trustops.adapter.in.messaging;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.trustops.application.ModerationService;
import com.afrotransact.trustops.domain.ListingFlagSource;
import com.afrotransact.trustops.domain.ListingReport;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.domain.ProductSubmission;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes catalog's product events ({@code at.product.events}, key = product id): {@code catalog.product.created}
 * with status IN_REVIEW is queued for moderation; {@code catalog.product.flagged} (a reported or flagged live listing)
 * is queued as a LIVE_LISTING item; {@code catalog.product.approved|rejected|taken_down} close an open item that was
 * decided directly in catalog. Malformed messages are logged and skipped; use-case failures propagate (retry).
 */
@Component
class CatalogProductEventListener {

    static final String CREATED = "catalog.product.created";
    static final String APPROVED = "catalog.product.approved";
    static final String REJECTED = "catalog.product.rejected";
    static final String FLAGGED = "catalog.product.flagged";
    static final String TAKEN_DOWN = "catalog.product.taken_down";
    private static final Set<String> HANDLED = Set.of(CREATED, APPROVED, REJECTED, FLAGGED, TAKEN_DOWN);

    private static final Logger LOG = LoggerFactory.getLogger(CatalogProductEventListener.class);

    private final ModerationService moderation;
    private final JsonMapper json;

    CatalogProductEventListener(ModerationService moderation, JsonMapper json) {
        this.moderation = moderation;
        this.json = json;
    }

    /** The CatalogProductEvent fields moderation needs (contracts/asyncapi/fragments/catalog.yaml). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductMessage(UUID productId, String atin, String ownerKind, String ownerOrgId, String status,
                          String title, String productTypeCode) {
    }

    /** The CatalogProductFlagged fields the live-listing queue needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FlagMessage(UUID flagId, String source, String reasonCode, String details, String reporterId,
                       String riskLevel) {
    }

    @KafkaListener(id = "trust-ops-catalog-products",
            groupId = "${spring.kafka.consumer.group-id:trust-ops-service}-moderation",
            topics = "${at.trustops.consumers.product-topic:at.product.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.type(rec);
        if (!HANDLED.contains(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            LOG.warn("Skipping {} at {}-{}@{}: missing or invalid event-id", type, rec.topic(), rec.partition(),
                    rec.offset());
            return;
        }
        ProductMessage m;
        try {
            m = json.readValue(rec.value(), ProductMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping malformed {} event {}: {}", type, eventId.get(), ex.getMessage());
            return;
        }
        if (m == null || m.productId() == null || m.ownerOrgId() == null || m.ownerOrgId().isBlank()) {
            LOG.warn("Skipping {} event {} without productId / ownerOrgId", type, eventId.get());
            return;
        }
        ProductSubmission listing = new ProductSubmission(m.productId(), m.atin(), m.ownerOrgId(), m.ownerKind(),
                m.title(), m.productTypeCode());
        if (CREATED.equals(type)) {
            if ("IN_REVIEW".equals(m.status())) {
                moderation.enqueue(eventId.get(), listing);
            }
        } else if (FLAGGED.equals(type)) {
            report(rec.value(), eventId.get()).ifPresent(r -> moderation.enqueueLiveListing(eventId.get(), listing,
                    r));
        } else {
            moderation.closeFromCatalog(eventId.get(), m.productId(), m.status() == null ? type : m.status());
        }
    }

    private Optional<ListingReport> report(String value, UUID eventId) {
        try {
            FlagMessage f = json.readValue(value, FlagMessage.class);
            if (f == null || f.source() == null) {
                LOG.warn("Skipping {} event {} without a flag source", FLAGGED, eventId);
                return Optional.empty();
            }
            ListingFlagSource source = ListingFlagSource.valueOf(f.source());
            return Optional.of(new ListingReport(f.flagId(), source, reason(f.reasonCode()), f.details(),
                    f.reporterId(), f.riskLevel(), 1));
        } catch (JacksonException | IllegalArgumentException ex) {
            LOG.warn("Skipping {} event {} with an unusable flag: {}", FLAGGED, eventId, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Catalog's listing reason codes match ours; an unknown code still queues the listing (as OTHER). */
    private static ModerationReasonCode reason(String code) {
        try {
            return code == null ? ModerationReasonCode.OTHER : ModerationReasonCode.valueOf(code);
        } catch (IllegalArgumentException ex) {
            return ModerationReasonCode.OTHER;
        }
    }
}
