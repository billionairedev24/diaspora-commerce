package com.afrotransact.retail.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.platform.events.InboxDeduplicator;
import com.afrotransact.retail.domain.GlPostingRules;
import com.afrotransact.retail.domain.JournalEntry;
import com.afrotransact.retail.domain.JournalSource;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.port.ItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CogsPostingServiceImpl implements CogsPostingService {

    static final String CONSUMER = "retail-cogs";
    private static final Logger LOG = LoggerFactory.getLogger(CogsPostingServiceImpl.class);

    private final InboxDeduplicator inbox;
    private final ItemRepository items;
    private final GlPostingRules gl;
    private final JournalPoster journal;
    private final RetailSettings settings;

    CogsPostingServiceImpl(InboxDeduplicator inbox, ItemRepository items, GlPostingRules gl, JournalPoster journal,
                           RetailSettings settings) {
        this.inbox = inbox;
        this.items = items;
        this.gl = gl;
        this.journal = journal;
        this.settings = settings;
    }

    @Override
    @Transactional
    public Optional<JournalEntry> onShipped(UUID eventId, String orderId, List<ShippedLine> lines) {
        if (!inbox.firstDelivery(CONSUMER, eventId)) {
            return Optional.empty();
        }
        Set<String> skus = lines.stream().map(ShippedLine::sku).collect(Collectors.toSet());
        Set<String> retail = items.findBySkus(skus).stream().map(RetailItem::sku).collect(Collectors.toSet());
        long cogs = 0;
        for (ShippedLine line : lines) {
            if (!retail.contains(line.sku())) {
                continue;
            }
            for (ShippedLine.LotCost lot : line.lots()) {
                // Customers pay and the books are kept in USD: the lot's USD value fixed at receiving, whatever
                // currency it was paid in.
                if (lot.unitCostUsdMinor() == null || !"USD".equals(settings.baseCurrency())) {
                    LOG.warn("Order {} lot {} ({}) has no USD value for base currency {}; COGS skipped", orderId,
                            lot.lotNo(), lot.currency(), settings.baseCurrency());
                    continue;
                }
                cogs = Math.addExact(cogs, Math.multiplyExact((long) lot.qty(), lot.unitCostUsdMinor()));
            }
        }
        if (cogs == 0) {
            return Optional.empty();
        }
        return Optional.of(journal.post(JournalSource.COGS, orderId, "COGS for shipped 1P order " + orderId
                + " (FEFO lots at landed cost)", gl.costOfGoodsSold(cogs), "inventory-events"));
    }
}
