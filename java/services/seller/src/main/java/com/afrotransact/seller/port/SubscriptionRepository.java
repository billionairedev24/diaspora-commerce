package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.PlanFeeCollection;
import com.afrotransact.seller.domain.Subscription;
import com.afrotransact.seller.domain.TrialSource;

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

    /** Subscriptions whose current billing period has ended by {@code now} and are due for renewal. */
    List<Subscription> dueRenewals(Instant now, int limit);

    /** Trial override, campaign, credit balance and plan fee collection (none when there is no subscription row). */
    SubscriptionTerms terms(String orgId);

    void setTrialOverride(String orgId, Integer days, String reason, String note, String actor);

    void setTrialSource(String orgId, TrialSource source, UUID campaignId);

    void setCreditBalance(String orgId, long creditCents);

    /** Adjusts the credit balance by delta (positive = add credit, negative = deduct credit) and returns the new balance. */
    long adjustCreditBalance(String orgId, long deltaCents);

    /** How the organisation pays its plan (S298) and who chose it (DEFAULT, ONBOARDING, SELLER, STAFF). */
    void setPlanFeeCollection(String orgId, PlanFeeCollection mode, String source, String actor, Instant at);

    /** Subscriptions on {@code planCode} at a version other than {@code version}. */
    List<Subscription> onOtherVersions(String planCode, int version);

    /** Every subscription (daily reward evaluation), in pages ordered by org id. */
    List<Subscription> page(String afterOrgId, int limit);
}
