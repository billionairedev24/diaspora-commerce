package com.afrotransact.offers.application;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.port.OfferRepository;
import com.afrotransact.offers.port.StockRepository;
import com.afrotransact.offers.port.StockRepository.StockLevel;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class StockSyncServiceImpl implements StockSyncService {

    static final String CONSUMER = "offers-inventory";

    private final StockRepository stock;
    private final OfferRepository offers;
    private final BuyBoxService buyBox;
    private final InboxDeduplicator inbox;

    StockSyncServiceImpl(StockRepository stock, OfferRepository offers, BuyBoxService buyBox,
                         InboxDeduplicator inbox) {
        this.stock = stock;
        this.offers = offers;
        this.buyBox = buyBox;
        this.inbox = inbox;
    }

    @Override
    @Transactional
    public void availabilityChanged(UUID eventId, StockLevel level) {
        if (!inbox.firstDelivery(CONSUMER, eventId) || !stock.apply(level)) {
            return;
        }
        Set<String> atins = new TreeSet<>();
        for (Offer o : offers.findOpenByInventorySku(level.ownerOrg(), level.sku())) {
            atins.add(o.atin());
        }
        if (!atins.isEmpty()) {
            buyBox.recompute(atins, "inventory.availability.changed");
        }
    }
}
