package com.afrotransact.deliveryruns.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.Channel;
import com.afrotransact.deliveryruns.domain.Subscription;

/** Subscriptions with their per-channel consent rows and an append-only consent log. */
public interface SubscriptionRepository {

    /** Inserts the subscription, its consent rows and a GRANTED log entry per granted channel. */
    Subscription insert(Subscription subscription);

    /** Updates status and consents; logs every consent that was granted or withdrawn by this change. */
    Subscription update(Subscription subscription);

    Optional<Subscription> findById(UUID id);

    Optional<Subscription> findByUser(UUID locationId, String userId);

    Optional<Subscription> findByEmail(UUID locationId, String email);

    Optional<Subscription> findByConfirmTokenHash(String tokenHash);

    Optional<Subscription> findByUnsubscribeToken(String token);

    List<Subscription> findAllByUser(String userId);

    /** Subscriber counts for one location, or every location when {@code locationId} is null. */
    List<SubscriberCounts> counts(UUID locationId);

    /** Active subscribers of a location with consent on {@code channel}, keyset-paged by subscription id. */
    List<Subscription> audience(UUID locationId, Channel channel, UUID after, int limit);

    /** New subscriptions per month since {@code since} (gross subscriber growth). */
    List<MonthlyCount> growth(UUID locationId, Instant since);

    /**
     * Sign-ups and unsubscribes per month since {@code since}, so growth is net of the people who left. A
     * subscription ended in the month of the last consent withdrawn in the consent log (its last update when the log
     * has none) while it is CANCELLED; one that signed up again counts as a subscriber again, not as having left.
     * Months with neither are absent.
     */
    List<NetGrowth> netGrowth(UUID locationId, Instant since);
}
