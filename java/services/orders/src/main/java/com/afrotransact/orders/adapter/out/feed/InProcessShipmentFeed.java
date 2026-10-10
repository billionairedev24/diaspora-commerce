package com.afrotransact.orders.adapter.out.feed;

import com.afrotransact.orders.port.LocalShipmentFeed;
import com.afrotransact.orders.port.ShipmentFeed;

/**
 * Single-replica feed ({@code at.orders.live-feed.fanout=local}): updates go only to this replica's subscribers.
 * For local runs and tests without Valkey.
 */
public class InProcessShipmentFeed implements ShipmentFeed {

    private final LocalShipmentFeed local;

    public InProcessShipmentFeed(LocalShipmentFeed local) {
        this.local = local;
    }

    @Override
    public void publish(Update update) {
        local.deliver(update);
    }
}
