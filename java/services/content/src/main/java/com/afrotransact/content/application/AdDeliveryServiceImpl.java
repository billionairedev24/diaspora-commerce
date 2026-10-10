package com.afrotransact.content.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.content.domain.ads.AdClick;
import com.afrotransact.content.domain.ads.Campaign;
import com.afrotransact.content.domain.ads.PlacedOrder;
import com.afrotransact.content.port.AdCampaignRepository;
import com.afrotransact.content.port.AdStatsRepository;
import com.afrotransact.content.port.AdStatsRepository.AttributableClick;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdDeliveryServiceImpl implements AdDeliveryService {

    static final String IMPRESSIONS = "content-ads-impressions";
    static final String CLICKS = "content-ads-clicks";
    static final String ORDERS = "content-ads-attribution";

    private static final Logger LOG = LoggerFactory.getLogger(AdDeliveryServiceImpl.class);

    private final AdCampaignRepository campaigns;
    private final AdStatsRepository stats;
    private final InboxDeduplicator inbox;
    private final DomainEventPublisher events;
    private final Clock clock;

    AdDeliveryServiceImpl(AdCampaignRepository campaigns, AdStatsRepository stats, InboxDeduplicator inbox,
                          DomainEventPublisher events, Clock clock) {
        this.campaigns = campaigns;
        this.stats = stats;
        this.inbox = inbox;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void impression(UUID eventId, String campaignId, Instant occurredAt) {
        if (!inbox.firstDelivery(IMPRESSIONS, eventId)) {
            return;
        }
        if (campaigns.findById(campaignId).isEmpty()) {
            LOG.warn("Impression {} for unknown campaign {} ignored", eventId, campaignId);
            return;
        }
        stats.addImpressions(campaignId, day(occurredAt), 1);
    }

    @Override
    @Transactional
    public ClickOutcome click(UUID eventId, AdClick click) {
        if (!inbox.firstDelivery(CLICKS, eventId)) {
            return new ClickOutcome(false, 0, false);
        }
        // The row lock serialises clicks of one campaign, so the budget is never overspent.
        Optional<Campaign> locked = campaigns.lock(click.campaignId());
        if (locked.isEmpty()) {
            LOG.warn("Click {} for unknown campaign {} ignored", click.clickId(), click.campaignId());
            return new ClickOutcome(false, 0, false);
        }
        if (stats.clickExists(click.clickId())) {
            return new ClickOutcome(false, 0, false);
        }
        LocalDate day = day(click.occurredAt());
        long spentToday = stats.spendOn(List.of(click.campaignId()), day).getOrDefault(click.campaignId(), 0L);
        Instant now = Instant.now(clock);
        Campaign.Charge charge = locked.get().charge(click.priceMinor(), spentToday, now);
        stats.insertClick(click, charge.chargedMinor());
        stats.addClick(click.campaignId(), day, charge.chargedMinor());
        if (charge.chargedMinor() > 0 || charge.justExhausted()) {
            campaigns.update(charge.campaign());
        }
        if (charge.justExhausted()) {
            LOG.info("Campaign {} spent its budget and was paused", click.campaignId());
            events.publish(ContentEvents.ADS_CAMPAIGN_PAUSED, ContentEvents.AGGREGATE, click.campaignId(),
                    ContentEventPayloads.campaignPaused(charge.campaign(), now));
        }
        return new ClickOutcome(true, charge.chargedMinor(), charge.justExhausted());
    }

    @Override
    @Transactional
    public int orderPlaced(UUID eventId, PlacedOrder order) {
        if (!inbox.firstDelivery(ORDERS, eventId) || order.customerId() == null || order.customerId().isBlank()) {
            return 0;
        }
        Instant from = order.placedAt().minus(PlacedOrder.ATTRIBUTION_WINDOW);
        LocalDate day = day(order.placedAt());
        Set<String> creditedCampaigns = new LinkedHashSet<>();
        int attributed = 0;
        for (PlacedOrder.Line line : order.lines()) {
            Optional<AttributableClick> click = stats.lastClick(order.customerId(), line.sellerOrgId(),
                    line.variantAtin(), from, order.placedAt());
            if (click.isEmpty()
                    || !stats.insertAttribution(order.orderId(), line.lineId(), click.get(), line.salesMinor(), Instant.now(clock))) {
                continue;
            }
            attributed++;
            boolean firstLineOfCampaign = creditedCampaigns.add(click.get().campaignId());
            stats.addAttributed(click.get().campaignId(), day, firstLineOfCampaign ? 1 : 0, line.salesMinor());
        }
        return attributed;
    }

    private static LocalDate day(Instant at) {
        return LocalDate.ofInstant(at, ZoneOffset.UTC);
    }
}
