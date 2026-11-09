package com.afrotransact.catalog.adapter.in.messaging;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.application.ProductTaxService;
import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code tax.classification.changed} from {@code at.taxreturns.events}: a variant's classification was
 * approved, rejected or went stale (kind VARIANT_CLASSIFICATION), or a category's tax profile changed status
 * (PROFILE_STATUS). Catalog keeps the status on the variant (the publish gate) and on the category node (what sellers see).
 * Malformed messages are logged and skipped; use-case failures propagate so the container retries the record.
 */
@Component
class TaxClassificationEventListener {

    static final String CLASSIFICATION_CHANGED = "tax.classification.changed";

    private static final Logger LOG = LoggerFactory.getLogger(TaxClassificationEventListener.class);

    private final ProductTaxService tax;
    private final JsonMapper json;

    TaxClassificationEventListener(ProductTaxService tax, JsonMapper json) {
        this.tax = tax;
        this.json = json;
    }

    /** The fields of the TaxClassificationChanged payload catalog needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ClassificationMessage(String kind, String categoryCode, String atin, String status) {
    }

    @KafkaListener(id = "catalog-tax-classification", groupId = "${spring.kafka.consumer.group-id:catalog-service}",
            topics = "${at.catalog.consumers.tax-classification-topic:at.taxreturns.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!CLASSIFICATION_CHANGED.equals(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            throw MalformedEvent.missingEventId(type, rec);
        }
        ClassificationMessage m;
        try {
            m = json.readValue(rec.value(), ClassificationMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null || m.kind() == null) {
            LOG.warn("Skipping {} event {} without kind", type, eventId.get());
            return;
        }
        switch (m.kind()) {
            case "VARIANT_CLASSIFICATION" -> tax.classificationChanged(m.atin(), m.status());
            case "PROFILE_STATUS" -> tax.profileStatusChanged(m.categoryCode(), m.status());
            default -> LOG.debug("Ignoring {} of kind {}", type, m.kind());
        }
    }
}
