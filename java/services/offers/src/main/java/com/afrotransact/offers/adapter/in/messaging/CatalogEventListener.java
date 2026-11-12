package com.afrotransact.offers.adapter.in.messaging;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.offers.application.CatalogSyncService;
import com.afrotransact.offers.application.OfferRelocationService;
import com.afrotransact.offers.application.ProductSnapshot;
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
 * Consumes {@code catalog.product.*} from {@code at.product.events} (key = product id). created → the creating
 * seller's offers; terms_changed → their price, quantity, "was" price, handling time and ship-from (S307); approved / resumed / reinstated / rejected / paused / taken_down → offers on the page become live
 * or not (a takedown suppresses them: off the buy box and every public read). Malformed
 * messages are logged and skipped; use-case failures propagate so the container retries the record.
 */
@Component
class CatalogEventListener {

    static final String CREATED = "catalog.product.created";
    static final String TERMS_CHANGED = "catalog.product.terms_changed";
    /** S386: two duplicate pages were merged; the offers of the merged page's variants move to the surviving page. */
    static final String MERGED = "catalog.product.merged";
    /** A variant moved to another page, or was split onto a page of its own (staff, after a wrong-match report). */
    static final String VARIANT_MOVED = "catalog.variant.moved";
    static final Set<String> STATUS_EVENTS = Set.of("catalog.product.approved", "catalog.product.rejected",
            "catalog.product.paused", "catalog.product.resumed", "catalog.product.taken_down",
            "catalog.product.reinstated");

    private static final Logger LOG = LoggerFactory.getLogger(CatalogEventListener.class);

    private final CatalogSyncService sync;
    private final OfferRelocationService relocation;
    private final JsonMapper json;

    CatalogEventListener(CatalogSyncService sync, OfferRelocationService relocation, JsonMapper json) {
        this.sync = sync;
        this.relocation = relocation;
        this.json = json;
    }

    /** Wire format of catalog's ProductEventPayload (contracts/asyncapi/fragments/catalog.yaml). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductMessage(UUID productId, String atin, String ownerKind, String ownerOrgId, String status,
                          UUID categoryId, List<VariantMessage> variants, String condition, String fulfilment,
                          Integer handlingDays, ShipFromMessage shipFrom) {
    }

    /** Wire format of catalog's catalog.product.merged payload. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MergedMessage(String loserAtin, UUID survivorProductId, String survivorAtin, UUID survivorCategoryId,
                         Map<String, String> variantMap) {
    }

    /** Wire format of catalog's catalog.variant.moved payload. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record MovedMessage(String variantAtin, UUID toProductId, String toAtin, UUID toCategoryId, String toStatus) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VariantMessage(String atin, String sellerSku, String gtin, long priceMinor, String currency,
                          int quantity, Long compareAtMinor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ShipFromMessage(String locationId, String city, String region, String country, String postalCode) {
    }

    private void merged(UUID eventId, ConsumerRecord<String, String> rec, String type) {
        MergedMessage m;
        try {
            m = json.readValue(rec.value(), MergedMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null || m.survivorAtin() == null || m.variantMap() == null || m.variantMap().isEmpty()) {
            LOG.warn("Skipping {} event {} without a surviving page or variant map", type, eventId);
            return;
        }
        relocation.pagesMerged(eventId, new OfferRelocationService.Merged(m.loserAtin(), m.survivorProductId(),
                m.survivorAtin(), m.survivorCategoryId(), m.variantMap()));
    }

    private void variantMoved(UUID eventId, ConsumerRecord<String, String> rec, String type) {
        MovedMessage m;
        try {
            m = json.readValue(rec.value(), MovedMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null || m.variantAtin() == null || m.toAtin() == null || m.toProductId() == null) {
            LOG.warn("Skipping {} event {} without a variant or the page it moved to", type, eventId);
            return;
        }
        relocation.variantMoved(eventId, new OfferRelocationService.VariantMoved(m.variantAtin(), m.toProductId(),
                m.toAtin(), m.toCategoryId(), "LIVE".equals(m.toStatus())));
    }

    @KafkaListener(id = "offers-catalog-products", groupId = "${spring.kafka.consumer.group-id:offers-service}",
            topics = "${at.offers.consumers.catalog-topic:at.product.events}",
            autoStartup = "${at.consumers.enabled:true}")
    void onMessage(ConsumerRecord<String, String> rec) {
        String type = EventHeaders.text(rec, EventHeaders.EVENT_TYPE).orElse("");
        if (!CREATED.equals(type) && !TERMS_CHANGED.equals(type) && !MERGED.equals(type)
                && !VARIANT_MOVED.equals(type) && !STATUS_EVENTS.contains(type)) {
            return;
        }
        Optional<UUID> eventId = EventHeaders.eventId(rec);
        if (eventId.isEmpty()) {
            throw MalformedEvent.missingEventId(type, rec);
        }
        if (MERGED.equals(type)) {
            merged(eventId.get(), rec, type);
            return;
        }
        if (VARIANT_MOVED.equals(type)) {
            variantMoved(eventId.get(), rec, type);
            return;
        }
        ProductMessage m;
        try {
            m = json.readValue(rec.value(), ProductMessage.class);
        } catch (JacksonException | IllegalArgumentException ex) {
            throw MalformedEvent.of(type, ex);
        }
        if (m == null || m.atin() == null || m.ownerOrgId() == null || m.status() == null) {
            LOG.warn("Skipping {} event {} without atin, owner or status", type, eventId.get());
            return;
        }
        ProductSnapshot snapshot = new ProductSnapshot(m.productId(), m.atin(), m.ownerKind(), m.ownerOrgId(),
                m.status(), m.categoryId(), m.variants() == null ? List.of() : m.variants().stream()
                        .map(v -> new ProductSnapshot.VariantSnapshot(v.atin(), v.sellerSku(), v.gtin(),
                                v.priceMinor(), v.currency(), v.quantity(), v.compareAtMinor()))
                        .toList(), m.condition(), m.fulfilment(), m.handlingDays(),
                m.shipFrom() == null ? null : new ProductSnapshot.ShipFromSnapshot(m.shipFrom().locationId(),
                        m.shipFrom().city(), m.shipFrom().region(), m.shipFrom().country(), m.shipFrom().postalCode()));
        if (CREATED.equals(type)) {
            sync.productCreated(eventId.get(), snapshot);
        } else if (TERMS_CHANGED.equals(type)) {
            sync.termsChanged(eventId.get(), snapshot);
        } else {
            sync.productStatusChanged(eventId.get(), type, snapshot);
        }
    }
}
