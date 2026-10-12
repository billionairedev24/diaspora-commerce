package com.afrotransact.offers.application;

import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
import com.afrotransact.offers.domain.ShipFrom;
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
                Fulfilment fulfilment = platform || "AFROTRANSACT".equals(p.fulfilment()) ? Fulfilment.AFROTRANSACT
                        : Fulfilment.SELLER;
                Offer offer = offers.insert(new Offer(UUID.randomUUID(), v.atin(), p.atin(), p.productId(), v.gtin(),
                        p.categoryId(), p.ownerOrgId(), platform ? SellerKind.PLATFORM : SellerKind.SELLER, sku, sku,
                        condition(p), v.priceMinor(), listPrice(v),
                        v.currency() == null ? "USD" : v.currency().toUpperCase(Locale.ROOT), 0,
                        fulfilment, fulfilment.sellerStock() ? Math.max(0, v.quantity()) : 0, handlingDays(p),
                        shipFrom(p, fulfilment), OfferStatus.ACTIVE, LIVE.equals(p.status()), now, now, 0));
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
    public void termsChanged(UUID eventId, ProductSnapshot p) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return;
        }
        Instant now = Instant.now(clock);
        Set<String> touched = new TreeSet<>();
        boolean missing = false;
        for (ProductSnapshot.VariantSnapshot v : p.variants()) {
            List<Offer> open = offers.findOpenBySellerAndAtin(p.ownerOrgId(), v.atin());
            if (open.isEmpty()) {
                missing = true;
                continue;
            }
            for (Offer o : open) {
                Offer.Terms t = o.terms();
                Offer.Terms next = new Offer.Terms(t.condition(), v.priceMinor(), listPrice(v), t.shippingMinor(),
                        t.fulfilment(), o.fulfilment().sellerStock() ? Math.max(0, v.quantity()) : t.sellerQuantity(),
                        p.handlingDays() == null || p.handlingDays() <= 0 ? t.handlingDays() : handlingDays(p),
                        o.fulfilment().sellerStock() && p.shipFrom() != null ? shipFrom(p, o.fulfilment()) : t.shipFrom(),
                        t.inventorySku());
                if (next.equals(t)) {
                    continue;
                }
                try {
                    Offer updated = offers.update(o.revise(next, now));
                    events.publish(OffersEvents.OFFER_UPDATED, OffersEvents.AGGREGATE, updated.atin(),
                            OfferEventPayload.of(updated));
                    touched.add(updated.atin());
                } catch (OfferRuleViolation | IllegalStateException ex) {
                    LOG.warn("Skipping terms of offer {} ({}): {}", o.id(), v.atin(), ex.getMessage());
                }
            }
        }
        if (!touched.isEmpty()) {
            buyBox.recompute(touched, "catalog.product.terms_changed");
        }
        if (missing) {
            // Variants added by an edit get their offers the way new products do.
            productCreated(UUID.nameUUIDFromBytes(("terms-" + eventId).getBytes(StandardCharsets.UTF_8)), p);
        }
    }

    private static OfferCondition condition(ProductSnapshot p) {
        if (p.condition() == null) {
            return OfferCondition.NEW;
        }
        try {
            return OfferCondition.valueOf(p.condition());
        } catch (IllegalArgumentException ex) {
            return OfferCondition.NEW;
        }
    }

    private static Long listPrice(ProductSnapshot.VariantSnapshot v) {
        return v.compareAtMinor() != null && v.compareAtMinor() > v.priceMinor() ? v.compareAtMinor() : null;
    }

    private int handlingDays(ProductSnapshot p) {
        return p.handlingDays() == null || p.handlingDays() <= 0
                ? settings.defaultHandlingDays() : Math.min(p.handlingDays(), Offer.MAX_HANDLING_DAYS);
    }

    private static ShipFrom shipFrom(ProductSnapshot p, Fulfilment fulfilment) {
        ProductSnapshot.ShipFromSnapshot s = p.shipFrom();
        if (s == null || !fulfilment.sellerStock() || s.country() == null) {
            return null;
        }
        return new ShipFrom(s.city(), s.region(), s.country(), s.postalCode(), s.locationId());
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
