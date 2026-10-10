package com.afrotransact.offers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
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
class FbaOfferServiceImpl implements FbaOfferService {

    static final String CONSUMER = "offers-inventory-inbound";
    static final String FBA = "FBA";
    private static final String TRIGGER = "inventory.inbound.received";
    private static final String FBA_SKU_SUFFIX = "-FBA";
    private static final Logger LOG = LoggerFactory.getLogger(FbaOfferServiceImpl.class);

    private final OfferRepository offers;
    private final BuyBoxService buyBox;
    private final InboxDeduplicator inbox;
    private final DomainEventPublisher events;
    private final OffersSettings settings;
    private final Clock clock;

    FbaOfferServiceImpl(OfferRepository offers, BuyBoxService buyBox, InboxDeduplicator inbox,
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
    public List<UUID> stockReceived(UUID eventId, ReceivedStock stock) {
        if (!inbox.firstDelivery(CONSUMER, eventId) || !FBA.equals(stock.source()) || stock.ownerOrg() == null) {
            return List.of();
        }
        Instant now = Instant.now(clock);
        List<UUID> created = new ArrayList<>();
        Set<String> touched = new TreeSet<>();
        for (ReceivedStock.Line line : stock.lines()) {
            if (line.qtyGood() <= 0 || line.atin() == null || line.sku() == null) {
                continue;
            }
            String atin = line.atin().trim().toUpperCase(Locale.ROOT);
            String sku = line.sku().trim().toUpperCase(Locale.ROOT);
            List<Offer> open = offers.findOpenBySellerAndAtin(stock.ownerOrg(), atin);
            Optional<Offer> fba = open.stream().filter(o -> o.fulfilment().fulfilledByAfroTransact()).findFirst();
            if (fba.isPresent()) {
                if (!fba.get().inventorySku().equals(sku)) {
                    LOG.warn("FBA offer {} of {} on {} sells inventory SKU {}, but {} was received ({})",
                            fba.get().id(), stock.ownerOrg(), atin, fba.get().inventorySku(), sku, stock.shipmentId());
                }
                continue;
            }
            Optional<Offer> template = open.stream().filter(o -> o.fulfilment().sellerStock())
                    .min(Comparator.comparing((Offer o) -> o.condition() != OfferCondition.NEW)
                            .thenComparing(Offer::createdAt).thenComparing(Offer::id));
            if (template.isEmpty()) {
                LOG.info("No offer of {} on {} to switch to Fulfilled by AfroTransact; {} received units of {} stay "
                        + "stranded until the seller creates one", stock.ownerOrg(), atin, line.qtyGood(), sku);
                continue;
            }
            Offer t = template.get();
            String sellerSku = offers.sellerSkuTaken(stock.ownerOrg(), t.sellerSku(), true) ? fbaSku(t.sellerSku())
                    : t.sellerSku();
            try {
                Offer offer = offers.insert(new Offer(UUID.randomUUID(), t.atin(), t.productAtin(), t.productId(),
                        t.gtin(), t.categoryId(), t.sellerOrgId(), t.sellerKind(), sellerSku, sku, t.condition(),
                        t.priceMinor(), t.listPriceMinor(), t.currency(), 0, Fulfilment.AFROTRANSACT, 0,
                        settings.defaultHandlingDays(), null, OfferStatus.ACTIVE, t.productLive(), now, now, 0));
                events.publish(OffersEvents.OFFER_CREATED, OffersEvents.AGGREGATE, offer.atin(),
                        OfferEventPayload.of(offer));
                created.add(offer.id());
                touched.add(offer.atin());
                LOG.info("Offer {} of {} on {} is now also Fulfilled by AfroTransact ({} from {})", offer.id(),
                        stock.ownerOrg(), atin, sku, stock.shipmentId());
            } catch (OfferRuleViolation ex) {
                LOG.warn("Cannot create the FBA offer of {} on {}: {} {}", stock.ownerOrg(), atin, ex.getMessage(),
                        ex.fieldErrors());
            }
        }
        if (!touched.isEmpty()) {
            buyBox.recompute(touched, TRIGGER);
        }
        return created;
    }

    private static String fbaSku(String sellerSku) {
        String base = sellerSku.length() + FBA_SKU_SUFFIX.length() > Offer.MAX_SKU
                ? sellerSku.substring(0, Offer.MAX_SKU - FBA_SKU_SUFFIX.length()) : sellerSku;
        return base + FBA_SKU_SUFFIX;
    }
}
