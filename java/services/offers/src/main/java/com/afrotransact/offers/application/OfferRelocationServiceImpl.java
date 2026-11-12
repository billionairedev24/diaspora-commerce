package com.afrotransact.offers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.platform.web.error.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OfferRelocationServiceImpl implements OfferRelocationService {

    static final String CONSUMER = "offers-catalog-merge";
    private static final Logger LOG = LoggerFactory.getLogger(OfferRelocationServiceImpl.class);

    private final OfferRepository offers;
    private final BuyBoxService buyBox;
    private final InboxDeduplicator inbox;
    private final DomainEventPublisher events;
    private final Clock clock;

    OfferRelocationServiceImpl(OfferRepository offers, BuyBoxService buyBox, InboxDeduplicator inbox,
                               DomainEventPublisher events, Clock clock) {
        this.offers = offers;
        this.buyBox = buyBox;
        this.inbox = inbox;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int pagesMerged(UUID eventId, Merged merged) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return 0;
        }
        Instant now = Instant.now(clock);
        // The surviving page is live as far as its own offers say (the merge event does not carry its status).
        boolean survivorLive = offers.findOpenByProductAtin(merged.survivorAtin()).stream().anyMatch(Offer::productLive);
        Set<String> touched = new TreeSet<>();
        int moved = 0;
        for (Map.Entry<String, String> pair : merged.variantMap().entrySet()) {
            String from = pair.getKey();
            String to = pair.getValue();
            for (Offer o : offers.findOpenByAtins(List.of(from))) {
                boolean fba = o.fulfilment().fulfilledByAfroTransact();
                try {
                    if (offers.findOpen(o.sellerOrgId(), to, o.condition(), fba).isPresent()) {
                        // The seller already sells this on the surviving page: that offer stays, this one goes.
                        withdraw(o, now);
                    } else {
                        Offer relocated = offers.relocate(o.relocatedTo(to, merged.survivorAtin(), merged.survivorProductId(),
                                merged.survivorCategoryId() != null ? merged.survivorCategoryId() : o.categoryId(),
                                survivorLive, now));
                        events.publish(OffersEvents.OFFER_UPDATED, OffersEvents.AGGREGATE, relocated.atin(),
                                OfferEventPayload.of(relocated));
                        moved++;
                    }
                    touched.add(from);
                    touched.add(to);
                } catch (ConflictException | IllegalStateException ex) {
                    // Changed meanwhile, or the same SKU is taken on the surviving page: keep the seller's offer there.
                    LOG.warn("Offer {} on {} was not moved to {}: {}", o.id(), from, to, ex.getMessage());
                    try {
                        withdraw(offers.findById(o.id()).orElse(o), now);
                        touched.add(from);
                    } catch (ConflictException | IllegalStateException again) {
                        LOG.warn("Offer {} could not be withdrawn either: {}", o.id(), again.getMessage());
                    }
                }
            }
        }
        if (!touched.isEmpty()) {
            buyBox.recompute(touched, "catalog.product.merged");
        }
        return moved;
    }

    private void withdraw(Offer o, Instant now) {
        Offer withdrawn = offers.update(o.withdraw(now));
        events.publish(OffersEvents.OFFER_WITHDRAWN, OffersEvents.AGGREGATE, withdrawn.atin(),
                OfferEventPayload.of(withdrawn));
    }
}
