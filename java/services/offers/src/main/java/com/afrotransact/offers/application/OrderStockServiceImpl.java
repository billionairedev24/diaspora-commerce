package com.afrotransact.offers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferRuleViolation;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OrderStockServiceImpl implements OrderStockService {

    static final String CONSUMER = "offers-orders";
    private static final Logger LOG = LoggerFactory.getLogger(OrderStockServiceImpl.class);

    private final OfferRepository offers;
    private final BuyBoxService buyBox;
    private final InboxDeduplicator inbox;
    private final DomainEventPublisher events;
    private final Clock clock;

    OrderStockServiceImpl(OfferRepository offers, BuyBoxService buyBox, InboxDeduplicator inbox,
                          DomainEventPublisher events, Clock clock) {
        this.offers = offers;
        this.buyBox = buyBox;
        this.inbox = inbox;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void orderPlaced(UUID eventId, List<SoldLine> lines) {
        apply(eventId, lines, -1, "orders.order.placed");
    }

    @Override
    @Transactional
    public void shipmentCancelled(UUID eventId, List<SoldLine> lines) {
        apply(eventId, lines, 1, "orders.shipment.cancelled");
    }

    private void apply(UUID eventId, List<SoldLine> lines, int sign, String trigger) {
        if (lines.isEmpty() || !inbox.firstDelivery(CONSUMER, eventId)) {
            return;
        }
        Instant now = Instant.now(clock);
        Set<String> touched = new TreeSet<>();
        for (SoldLine l : lines) {
            if (l.quantity() <= 0 || l.sellerOrgId() == null || l.variantAtin() == null) {
                continue;
            }
            Offer offer = offers.findOpenBySellerAndAtin(l.sellerOrgId(), l.variantAtin().toUpperCase()).stream()
                    .filter(o -> o.fulfilment().sellerStock()).findFirst().orElse(null);
            if (offer == null) {
                LOG.debug("No seller-fulfilled offer of {} on {} to change by {}", l.sellerOrgId(), l.variantAtin(),
                        sign * l.quantity());
                continue;
            }
            int next = Math.clamp((long) offer.sellerQuantity() + (long) sign * l.quantity(), 0,
                    OfferServiceImpl.MAX_SELLER_QUANTITY);
            if (next == offer.sellerQuantity()) {
                continue;
            }
            Offer.Terms t = offer.terms();
            try {
                Offer updated = offers.update(offer.revise(new Offer.Terms(t.condition(), t.priceMinor(),
                        t.listPriceMinor(), t.shippingMinor(), t.fulfilment(), next, t.handlingDays(), t.shipFrom(),
                        t.inventorySku()), now));
                events.publish(OffersEvents.OFFER_UPDATED, OffersEvents.AGGREGATE, updated.atin(),
                        OfferEventPayload.of(updated));
                touched.add(updated.atin());
            } catch (OfferRuleViolation | IllegalStateException ex) {
                LOG.warn("Stock of offer {} not changed by {}: {}", offer.id(), trigger, ex.getMessage());
            }
        }
        if (!touched.isEmpty()) {
            buyBox.recompute(touched, trigger);
        }
    }
}
