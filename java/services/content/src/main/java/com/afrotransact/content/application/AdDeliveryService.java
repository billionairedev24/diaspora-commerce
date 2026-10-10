package com.afrotransact.content.application;

import java.time.Instant;
import java.util.UUID;

import com.afrotransact.content.domain.ads.AdClick;
import com.afrotransact.content.domain.ads.PlacedOrder;

/**
 * Applies ad delivery events: impressions and clicks from search-service (at.ads.events) and orders for attribution
 * (at.order.events). Every method is idempotent per event id (inbox) and per click id / order line.
 */
public interface AdDeliveryService {

    /** Counts an impression (ads.impression.recorded). */
    void impression(UUID eventId, String campaignId, Instant occurredAt);

    /** Charges a click (ads.click.recorded): at most the second price and the budget left; pauses at zero. */
    ClickOutcome click(UUID eventId, AdClick click);

    /** Attributes the order's lines to the shopper's last ad click on them within 7 days (orders.order.placed). */
    int orderPlaced(UUID eventId, PlacedOrder order);

    /**
     * What a click did.
     *
     * @param recorded     false for a duplicate or an unknown campaign
     * @param chargedMinor amount charged
     * @param paused       the click spent the last of the budget and paused the campaign
     */
    record ClickOutcome(boolean recorded, long chargedMinor, boolean paused) {
    }
}
