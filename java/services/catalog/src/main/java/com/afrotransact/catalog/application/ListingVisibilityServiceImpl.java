package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.port.ListingSuppressionRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ListingVisibilityServiceImpl implements ListingVisibilityService {

    static final String CONSUMER = "catalog.seller-compliance";

    private static final Logger LOG = LoggerFactory.getLogger(ListingVisibilityServiceImpl.class);

    private final InboxDeduplicator inbox;
    private final ListingSuppressionRepository suppressions;
    private final ProductRepository products;
    private final ProductEvents events;
    private final Clock clock;

    ListingVisibilityServiceImpl(InboxDeduplicator inbox, ListingSuppressionRepository suppressions,
                                 ProductRepository products, ProductEvents events, Clock clock) {
        this.inbox = inbox;
        this.suppressions = suppressions;
        this.products = products;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int hide(ListingEnforcement event) {
        return apply(event, true);
    }

    @Override
    @Transactional
    public int restore(ListingEnforcement event) {
        return apply(event, false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hidden(String orgId) {
        return suppressions.isHidden(orgId);
    }

    private int apply(ListingEnforcement e, boolean hide) {
        if (!inbox.firstDelivery(CONSUMER, e.eventId())) {
            return 0;
        }
        if (e.orgId() == null || e.orgId().isBlank()) {
            LOG.warn("Ignoring listing enforcement event {} without orgId", e.eventId());
            return 0;
        }
        Instant now = Instant.now(clock);
        Instant at = e.occurredAt() == null ? now : e.occurredAt();
        String reason = e.reason() == null || e.reason().isBlank() ? (hide ? "MANUAL" : "CLEARED") : e.reason();
        if (!suppressions.apply(e.orgId(), hide, reason, at, now)) {
            return 0;
        }
        String type = hide ? CatalogEvents.PRODUCT_HIDDEN : CatalogEvents.PRODUCT_RESTORED;
        int published = 0;
        for (Product p : products.findByOwnerOrg(e.orgId())) {
            events.publish(type, p, hide);
            published++;
        }
        LOG.info("Listings of {} {} ({}): {} product events", e.orgId(), hide ? "hidden" : "restored", reason,
                published);
        return published;
    }
}
