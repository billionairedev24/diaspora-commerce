package com.afrotransact.offers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RetailListingSyncServiceImpl implements RetailListingSyncService {

    static final String CONSUMER = "offers-retail";
    static final String LIVE = "LIVE";
    static final String PAUSED = "PAUSED";
    private static final String TRIGGER = "retail.item.listing-changed";
    private static final Logger LOG = LoggerFactory.getLogger(RetailListingSyncServiceImpl.class);

    private final OfferRepository offers;
    private final BuyBoxService buyBox;
    private final InboxDeduplicator inbox;
    private final DomainEventPublisher events;
    private final OffersSettings settings;
    private final Clock clock;

    RetailListingSyncServiceImpl(OfferRepository offers, BuyBoxService buyBox, InboxDeduplicator inbox,
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
    public boolean itemChanged(UUID eventId, RetailItem item) {
        if (!inbox.firstDelivery(CONSUMER, eventId) || item.atin() == null || item.sku() == null
                || !(LIVE.equals(item.listingStatus()) || PAUSED.equals(item.listingStatus()))) {
            return false;
        }
        String atin = item.atin().trim().toUpperCase(Locale.ROOT);
        List<Offer> open = offers.findOpenBySellerAndAtin(settings.retailOrgId(), atin);
        Optional<Offer> found = open.stream().filter(o -> o.fulfilment().fulfilledByAfroTransact()).findFirst();
        if (found.isEmpty()) {
            LOG.info("No open AfroTransact Retail offer on {} for retail item {} ({}); the catalog product must exist "
                    + "first", atin, item.sku(), item.listingStatus());
            return false;
        }
        Offer current = found.get();
        Instant now = Instant.now(clock);
        Offer next = current;
        if (LIVE.equals(item.listingStatus())) {
            String sku = item.sku().trim().toUpperCase(Locale.ROOT);
            long price = item.priceMinor() != null && item.priceMinor() > 0
                    && current.currency().equalsIgnoreCase(item.currency()) ? item.priceMinor() : current.priceMinor();
            Long list = current.listPriceMinor() != null && current.listPriceMinor() < price ? null
                    : current.listPriceMinor();
            Offer.Terms t = current.terms();
            try {
                next = current.revise(new Offer.Terms(t.condition(), price, list, t.shippingMinor(), t.fulfilment(),
                        t.sellerQuantity(), t.handlingDays(), t.shipFrom(), sku), now);
            } catch (OfferRuleViolation ex) {
                LOG.warn("Cannot list retail item {} on offer {}: {} {}", item.sku(), current.id(), ex.getMessage(),
                        ex.fieldErrors());
                return false;
            }
            if (next.status() == OfferStatus.PAUSED) {
                next = next.resume(now);
            }
        } else if (current.status() == OfferStatus.ACTIVE) {
            next = current.pause(now);
        }
        if (next.terms().equals(current.terms()) && next.status() == current.status()) {
            return false;
        }
        Offer saved = offers.update(next);
        events.publish(OffersEvents.OFFER_UPDATED, OffersEvents.AGGREGATE, saved.atin(), OfferEventPayload.of(saved));
        buyBox.recompute(Set.of(saved.atin()), TRIGGER);
        LOG.info("AfroTransact Retail offer {} on {} follows retail item {}: {} · inventory SKU {} · {}", saved.id(),
                atin, item.itemId(), saved.status(), saved.inventorySku(), saved.priceMinor());
        return true;
    }
}
