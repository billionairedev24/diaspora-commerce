package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.afrotransact.seller.domain.Subscription;

/** One subscription per organisation. */
public interface SubscriptionRepository {

    boolean insert(Subscription subscription);

    Subscription update(Subscription subscription);

    Optional<Subscription> find(String orgId);

    Optional<Subscription> lock(String orgId);

    Optional<Subscription> lockByStripeSubscription(String subscriptionId);

    Optional<Subscription> findByCustomer(String customerId);

    List<Subscription> pendingActivation(int limit);

    /** Subscriptions whose pending plan / cadence change is due at {@code now}. */
    List<Subscription> dueChanges(Instant now, int limit);
}
