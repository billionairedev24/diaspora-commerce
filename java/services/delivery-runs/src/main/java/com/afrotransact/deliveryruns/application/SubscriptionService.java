package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Channel;
import com.afrotransact.deliveryruns.domain.Subscription;
import com.afrotransact.deliveryruns.port.SubscriberCounts;

/** Location run alerts with per-channel consent (S262). */
public interface SubscriptionService {

    /** Signed-in shopper: subscribe (or add channels); active at once. */
    Subscription subscribeMine(UUID locationId, Set<Channel> channels, String source);

    List<Subscription> mine();

    /** Signed-in shopper: withdraw channels (all when empty). */
    Subscription unsubscribeMine(UUID locationId, Set<Channel> channels);

    /**
     * Anonymous email sign-up (double opt-in): notifications sends the confirmation link from
     * {@code delivery-runs.subscription.created}. Same answer whether or not the address was known.
     */
    void subscribeEmail(UUID locationId, String email, String source);

    /** Confirmation link used; the subscription becomes active. */
    Subscription confirm(String token);

    /** One-click unsubscribe from a message (channels empty = all). */
    Subscription unsubscribe(String token, Set<Channel> channels);

    /** Staff: counts per location (all locations when null) and channel. */
    List<SubscriberCounts> counts(UUID locationId);

    /** notifications-service: opted-in subscribers of a location on a channel, keyset-paged. */
    AudiencePage audience(UUID locationId, Channel channel, UUID after, int limit);
}
