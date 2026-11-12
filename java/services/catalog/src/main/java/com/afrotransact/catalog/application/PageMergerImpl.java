package com.afrotransact.catalog.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.GtinReader;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.port.AtinRedirectRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.BusinessRuleException;
import org.springframework.stereotype.Component;

@Component
class PageMergerImpl implements PageMerger {

    private final ProductRepository products;
    private final AtinRedirectRepository redirects;
    private final ProductEvents productEvents;
    private final DomainEventPublisher events;

    PageMergerImpl(ProductRepository products, AtinRedirectRepository redirects, ProductEvents productEvents,
                   DomainEventPublisher events) {
        this.products = products;
        this.redirects = redirects;
        this.productEvents = productEvents;
        this.events = events;
    }

    @Override
    public Merged merge(Product loser, Product survivor, Map<String, String> requested, String by, String reason,
                        UUID changeRequestId, Instant now) {
        if (loser.id().equals(survivor.id())) {
            throw new BusinessRuleException("catalog.merge-invalid", "A page cannot be merged into itself");
        }
        if (loser.status() == ProductStatus.MERGED || survivor.status() == ProductStatus.MERGED
                || survivor.status() == ProductStatus.REJECTED) {
            throw new BusinessRuleException("catalog.merge-invalid",
                    "Both pages must still exist: a merged or rejected page cannot be merged");
        }
        Map<String, String> variantMap = mapVariants(loser, survivor, requested);

        Product merged = products.update(loser.mergedAway(now));
        Map<String, String> redirect = new LinkedHashMap<>(variantMap);
        redirect.put(loser.atin().value(), survivor.atin().value());
        redirects.saveAll(redirect, by, reason, now);
        // Pages that were merged into the loser earlier now redirect to the survivor (no chains).
        redirects.repoint(loser.atin().value(), survivor.atin().value());
        events.publish(CatalogEvents.PRODUCT_MERGED, CatalogEvents.AGGREGATE_PRODUCT, survivor.id().toString(),
                payload(loser, survivor, variantMap, by, reason, changeRequestId, now));
        productEvents.publish(CatalogEvents.PRODUCT_UPDATED, merged);
        return new Merged(merged, survivor, variantMap);
    }

    private static Map<String, String> mapVariants(Product loser, Product survivor, Map<String, String> requested) {
        Map<String, String> map = new LinkedHashMap<>();
        List<Variant> targets = survivor.variants();
        for (Variant from : loser.variants()) {
            String fromAtin = from.atin().value();
            String named = requested == null ? null : requested.get(fromAtin);
            Variant to = named != null ? targets.stream().filter(t -> t.atin().value().equals(named)).findFirst()
                    .orElseThrow(() -> new BusinessRuleException("catalog.merge-unmapped-variant",
                            named + " is not a variant of the page that survives"))
                    : autoMap(from, loser, targets, survivor);
            if (to == null) {
                throw new BusinessRuleException("catalog.merge-unmapped-variant", "Variant " + fromAtin
                        + " has no matching variant on the surviving page: say which variant it becomes");
            }
            map.put(fromAtin, to.atin().value());
        }
        return map;
    }

    /** By barcode, then identical options, then the only variant of a single-variant pair. */
    private static Variant autoMap(Variant from, Product loser, List<Variant> targets, Product survivor) {
        if (from.gtin() != null && !from.gtin().isBlank()) {
            String key = GtinReader.key14(Gtin.digits(from.gtin()));
            for (Variant t : targets) {
                if (t.gtin() != null && !t.gtin().isBlank() && GtinReader.key14(Gtin.digits(t.gtin())).equals(key)) {
                    return t;
                }
            }
        }
        if (!from.options().isEmpty()) {
            for (Variant t : targets) {
                if (Objects.equals(from.options(), t.options())) {
                    return t;
                }
            }
        }
        return loser.variants().size() == 1 && survivor.variants().size() == 1 ? targets.getFirst() : null;
    }

    private static Map<String, Object> payload(Product loser, Product survivor, Map<String, String> variantMap, String by,
                                               String reason, UUID changeRequestId, Instant now) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("loserProductId", loser.id().toString());
        p.put("loserAtin", loser.atin().value());
        p.put("survivorProductId", survivor.id().toString());
        p.put("survivorAtin", survivor.atin().value());
        p.put("variantMap", variantMap);
        p.put("mergedBy", by);
        p.put("reason", reason);
        p.put("changeRequestId", changeRequestId == null ? null : changeRequestId.toString());
        p.put("occurredAt", now.toString());
        return p;
    }
}
