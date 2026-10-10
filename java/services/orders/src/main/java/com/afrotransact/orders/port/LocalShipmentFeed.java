package com.afrotransact.orders.port;

/**
 * The live-feed subscribers connected to this replica (server-sent events). {@link ShipmentFeed} implementations
 * deliver every update here — directly, or after fanning it out to every replica over Valkey pub/sub (S219).
 */
public interface LocalShipmentFeed {

    /** Pushes one update to the matching subscribers of this replica. */
    void deliver(ShipmentFeed.Update update);
}
