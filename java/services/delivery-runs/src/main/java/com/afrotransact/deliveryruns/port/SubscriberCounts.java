package com.afrotransact.deliveryruns.port;

import java.util.Map;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Channel;

/**
 * Subscribers of a location.
 *
 * @param locationId location
 * @param total      every subscription ever made (cancelled included)
 * @param active     active subscriptions
 * @param pending    email subscribers who have not confirmed yet
 * @param byChannel  active subscriptions with consent in force, per channel
 */
public record SubscriberCounts(UUID locationId, long total, long active, long pending, Map<Channel, Long> byChannel) {

    public SubscriberCounts {
        byChannel = Map.copyOf(byChannel);
    }
}
