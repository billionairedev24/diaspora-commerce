package com.afrotransact.taxreturns.adapter.in.messaging;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.platform.events.consumer.MalformedEvent;
import com.afrotransact.taxreturns.application.VariantClassificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code catalog.product.tax-facts-changed} from the product topic: creates or refreshes the classification
 * record of every variant in the event and queues it for tax review. Replaying an event changes nothing (the refresh
 * compares facts). Other product events are ignored; malformed messages are logged and skipped.
 */
@Component
class CatalogTaxFactsListener {

    static final String TAX_FACTS_CHANGED = "catalog.product.tax-facts-changed";

    private static final Logger LOG = LoggerFactory.getLogger(CatalogTaxFactsListener.class);

    private final VariantClassificationService classifications;
    private final JsonMapper json;

    CatalogTaxFactsListener(VariantClassificationService classifications, JsonMapper json) {
        this.classifications = classifications;
        this.json = json;
    }

    @KafkaListener(id = "taxreturns-catalog", groupId = "${spring.kafka.consumer.group-id:tax-returns-service}",
            topics = "${at.taxreturns.consumers.catalog-topic:at.product.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!TAX_FACTS_CHANGED.equals(type)) {
            return;
        }
        JsonNode n;
        try {
            n = json.readTree(rec.value());
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        VariantClassificationService.CatalogFactsChanged event;
        try {
            event = parse(n);
        } catch (IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (event == null) {
            LOG.warn("Skipping {}: categoryCode or variants missing", type);
            return;
        }
        classifications.onCatalogFactsChanged(event);
    }

    /** The event as a command, or null when it carries nothing to classify. */
    VariantClassificationService.CatalogFactsChanged parse(JsonNode n) {
        String category = Json.text(n, "categoryCode");
        List<VariantClassificationService.Variant> variants = new ArrayList<>();
        for (JsonNode v : n.path("variants")) {
            String atin = Json.text(v, "atin");
            if (atin != null) {
                variants.add(new VariantClassificationService.Variant(atin, Json.text(v, "gtin"), values(v.path("facts")),
                        v.path("evidenceChanged").asBoolean(false)));
            }
        }
        if (category == null || category.isBlank() || variants.isEmpty()) {
            return null;
        }
        UUID productId = Json.uuid(n, "productId");
        return new VariantClassificationService.CatalogFactsChanged(productId, Json.text(n, "ownerOrgId"), category,
                Json.text(n, "productTypeCode"), values(n.path("sellerBehaviour")), variants, Json.text(n, "changedBy"),
                Json.instant(n, "occurredAt"));
    }

    /** Fact values as typed JSON gives them: booleans, whole numbers, decimals and text; nulls and nesting dropped. */
    static Map<String, Object> values(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        node.properties().forEach(e -> {
            JsonNode v = e.getValue();
            if (v.isBoolean()) {
                out.put(e.getKey(), v.asBoolean());
            } else if (v.isIntegralNumber()) {
                out.put(e.getKey(), v.asLong());
            } else if (v.isNumber()) {
                out.put(e.getKey(), v.decimalValue());
            } else if (v.isString()) {
                out.put(e.getKey(), v.asString());
            }
        });
        return out;
    }
}
