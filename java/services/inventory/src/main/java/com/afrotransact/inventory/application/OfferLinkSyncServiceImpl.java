package com.afrotransact.inventory.application;

import java.util.Locale;
import java.util.UUID;

import com.afrotransact.inventory.port.OfferLinkRepository;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OfferLinkSyncServiceImpl implements OfferLinkSyncService {

    static final String CONSUMER = "inventory-offers";
    static final String FBA = "AFROTRANSACT";

    private final OfferLinkRepository links;
    private final InboxDeduplicator inbox;

    OfferLinkSyncServiceImpl(OfferLinkRepository links, InboxDeduplicator inbox) {
        this.links = links;
        this.inbox = inbox;
    }

    @Override
    @Transactional
    public void offerChanged(UUID eventId, OfferState o) {
        if (!inbox.firstDelivery(CONSUMER, eventId) || o.offerId() == null || o.sellerOrgId() == null) {
            return;
        }
        String sku = o.inventorySku() == null || o.inventorySku().isBlank() ? null
                : o.inventorySku().trim().toUpperCase(Locale.ROOT);
        String status = o.status() == null ? "ACTIVE" : o.status();
        links.upsert(o.offerId(), o.sellerOrgId(), sku, o.atin(), status,
                "ACTIVE".equals(status) && o.productLive(), FBA.equals(o.fulfilment()), o.occurredAt());
    }
}
