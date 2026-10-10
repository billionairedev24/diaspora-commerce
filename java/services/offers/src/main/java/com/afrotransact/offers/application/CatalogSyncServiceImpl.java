package com.afrotransact.offers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CatalogSyncServiceImpl implements CatalogSyncService {

    static final String CONSUMER = "offers-catalog";
    private static final Logger LOG = LoggerFactory.getLogger(CatalogSyncServiceImpl.class);
    private static final String LIVE = "LIVE";

    private final OfferRepository offers;
    private final BuyBoxService buyBox;
    private final InboxDeduplicator inbox;
    private final DomainEventPublisher events;
    private final OffersSettings settings;
    private final Clock clock;

    CatalogSyncServiceImpl(OfferRepository offers, BuyBoxService buyBox, InboxDeduplicator inbox,
                           DomainEventPublisher events, OffersSettings settings, Clock clock) {
        this.offers = offers;
        this.buyBox = buyBox;
        this.inbox = inbox;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void productCreated(UUID eventId, ProductSnapshot p) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return;
        }
        Instant now = Instant.now(clock);
        boolean platform = "PLATFORM".equals(p.ownerKind()) || p.ownerOrgId().equals(settings.retailOrgId());
        Set<String> touched = new TreeSet<>();
        for (ProductSnapshot.VariantSnapshot v : p.variants()) {
            String sku = v.sellerSku() == null ? v.atin() : v.sellerSku().trim().toUpperCase(Locale.ROOT);
            if (offers.findOpen(p.ownerOrgId(), v.atin(), OfferCondition.NEW, platform).isPresent()
                    || offers.sellerSkuTaken(p.ownerOrgId(), sku, platform)) {
                LOG.debug("Offer for {} / {} already exists; skipping", p.ownerOrgId(), v.atin());
                continue;
            }
            try {
                Offer offer = offers.insert(new Offer(UUID.randomUUID(), v.atin(), p.atin(), p.productId(), v.gtin(),
                        p.categoryId(), p.ownerOrgId(), platform ? SellerKind.PLATFORM : SellerKind.SELLER, sku, sku,
                        OfferCondition.NEW, v.priceMinor(), null,
                        v.currency() == null ? "USD" : v.currency().toUpperCase(Locale.ROOT), 0,
                        platform ? Fulfilment.AFROTRANSACT : Fulfilment.SELLER, Math.max(0, v.quantity()),
                        settings.defaultHandlingDays(), null, OfferStatus.ACTIVE, LIVE.equals(p.status()), now, now,
                        0));
                events.publish(OffersEvents.OFFER_CREATED, OffersEvents.AGGREGATE, offer.atin(),
                        OfferEventPayload.of(offer));
                touched.add(offer.atin());
            } catch (OfferRuleViolation ex) {
                LOG.warn("Skipping offer for variant {} of {}: {} {}", v.atin(), p.atin(), ex.getMessage(),
                        ex.fieldErrors());
            }
        }
        if (!touched.isEmpty()) {
            buyBox.recompute(touched, "catalog.product.created");
        }
    }

    @Override
    @Transactional
    public void productStatusChanged(UUID eventId, String eventType, ProductSnapshot p) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return;
        }
        boolean live = LIVE.equals(p.status());
        Instant now = Instant.now(clock);
        Set<String> touched = new TreeSet<>();
        for (Offer o : offers.findOpenByProductAtin(p.atin())) {
            if (o.productLive() != live) {
                Offer updated = offers.update(o.withProductLive(live, now));
                events.publish(OffersEvents.OFFER_UPDATED, OffersEvents.AGGREGATE, updated.atin(),
                        OfferEventPayload.of(updated));
                touched.add(updated.atin());
            }
        }
        if (!touched.isEmpty()) {
            buyBox.recompute(touched, eventType);
        }
    }
}
