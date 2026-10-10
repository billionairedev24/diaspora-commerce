package com.afrotransact.catalog.application;

import com.afrotransact.catalog.domain.Product;
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
}
