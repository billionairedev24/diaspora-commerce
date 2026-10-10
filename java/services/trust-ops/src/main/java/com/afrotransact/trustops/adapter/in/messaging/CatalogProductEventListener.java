package com.afrotransact.trustops.adapter.in.messaging;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.trustops.application.ModerationService;
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
 * with status IN_REVIEW is queued for moderation; {@code catalog.product.approved|rejected} close an open item that
 * was decided directly in catalog. Malformed messages are logged and skipped; use-case failures propagate (retry).
 */
@Component
class CatalogProductEventListener {

    static final String CREATED = "catalog.product.created";
    static final String APPROVED = "catalog.product.approved";
    static final String REJECTED = "catalog.product.rejected";

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

    @KafkaListener(id = "trust-ops-catalog-products",
            groupId = "${spring.kafka.consumer.group-id:trust-ops-service}-moderation",
            topics = "${at.trustops.consumers.product-topic:at.product.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.type(rec);
        if (!CREATED.equals(type) && !APPROVED.equals(type) && !REJECTED.equals(type)) {
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
        if (CREATED.equals(type)) {
            if ("IN_REVIEW".equals(m.status())) {
                moderation.enqueue(eventId.get(), new ProductSubmission(m.productId(), m.atin(), m.ownerOrgId(),
                        m.ownerKind(), m.title(), m.productTypeCode()));
            }
        } else {
            moderation.closeFromCatalog(eventId.get(), m.productId(), m.status() == null ? type : m.status());
        }
    }
}
