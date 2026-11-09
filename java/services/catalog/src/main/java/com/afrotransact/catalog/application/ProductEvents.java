package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductFlag;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.port.ListingSuppressionRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Component;

/** Publishes catalog.product.* events, stamping whether the owner's listings are currently hidden. */
@Component
class ProductEvents {

    private final DomainEventPublisher events;
    private final ListingSuppressionRepository suppressions;

    ProductEvents(DomainEventPublisher events, ListingSuppressionRepository suppressions) {
        this.events = events;
        this.suppressions = suppressions;
    }

    void publish(String type, Product p) {
        publish(type, p, suppressions.isHidden(p.owner().orgId()));
    }

    void publish(String type, Product p, boolean sellerHidden) {
        events.publish(type, CatalogEvents.AGGREGATE_PRODUCT, p.id().toString(),
                ProductEventPayload.of(p, sellerHidden));
    }

    /**
     * {@code catalog.product.tax-facts-changed} (key = product id): the variants' facts and the seller's behaviour
     * facts, with what changed since the previous state. Nothing is published for a product whose category has no tax
     * profile (nothing to classify yet).
     *
     * @param previousNode the category the product left, when it moved; null otherwise
     * @param previous     the variants before the change (empty for a new product)
     * @param changedIds   the fact ids whose answers changed
     */
    void publishTaxFactsChanged(Product p, CategoryNode node, CategoryNode previousNode, List<Variant> previous,
                                Set<String> changedIds) {
        publishTaxFactsChanged(p, node, previousNode, previous, changedIds, Set.of());
    }

    /** As above; {@code evidenceAtins} are the variants whose evidence file changed. */
    void publishTaxFactsChanged(Product p, CategoryNode node, CategoryNode previousNode, List<Variant> previous,
                                Set<String> changedIds, Set<String> evidenceAtins) {
        if (node == null || node.taxProfileId() == null) {
            return;
        }
        Map<String, Variant> before = new HashMap<>();
        previous.forEach(v -> before.put(v.atin().value(), v));
        List<Map<String, Object>> variants = new ArrayList<>();
        for (Variant v : p.variants()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("atin", v.atin().value());
            m.put("sellerSku", v.sellerSku());
            m.put("gtin", v.gtin());
            m.put("facts", v.tax().facts());
            Variant was = before.get(v.atin().value());
            List<String> changed = new ArrayList<>(v.tax().facts().keySet().stream()
                    .filter(k -> was == null || !String.valueOf(was.tax().facts().get(k)).equals(String.valueOf(v.tax().facts().get(k))))
                    .sorted().toList());
            if (was != null) {
                was.tax().facts().keySet().stream().filter(k -> !v.tax().facts().containsKey(k)).sorted().forEach(changed::add);
            }
            m.put("changedFactIds", changed);
            m.put("evidenceChanged", evidenceAtins.contains(v.atin().value()));
            variants.add(m);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", p.id().toString());
        payload.put("atin", p.atin().value());
        payload.put("ownerOrgId", p.owner().orgId());
        payload.put("categoryCode", node.taxProfileId());
        payload.put("productTypeCode", p.productTypeCode());
        payload.put("previousCategoryCode", previousNode == null ? null : previousNode.taxProfileId());
        payload.put("sellerBehaviour", p.details().sellerBehaviour());
        payload.put("variants", variants);
        payload.put("changedBy", p.owner().orgId());
        payload.put("occurredAt", Instant.now().toString());
        events.publish(CatalogEvents.PRODUCT_TAX_FACTS_CHANGED, CatalogEvents.AGGREGATE_PRODUCT, p.id().toString(),
                payload);
    }

    /** {@code catalog.product.taken_down} carries the takedown reason code. */
    void publishWithReason(String type, Product p, String reasonCode) {
        events.publish(type, CatalogEvents.AGGREGATE_PRODUCT, p.id().toString(),
                ProductEventPayload.of(p, suppressions.isHidden(p.owner().orgId()), reasonCode));
    }

    /** {@code catalog.product.flagged} (key = product id, so it stays in order with the product's lifecycle). */
    void publishFlag(Product p, ProductFlag flag) {
        events.publish(CatalogEvents.PRODUCT_FLAGGED, CatalogEvents.AGGREGATE_PRODUCT, p.id().toString(),
                ProductFlaggedPayload.of(p, flag, suppressions.isHidden(p.owner().orgId())));
    }
}
