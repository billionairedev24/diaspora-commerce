package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.MembershipBenefitUse;
import com.afrotransact.payments.domain.MembershipEvent;

/** What members did and what happened to their memberships: benefit uses, lifecycle events, trial and grant claims. */
public interface MembershipActivityRepository {

    /** Records a benefit use; false when this benefit already applied to this order and ref (replay). */
    boolean insertUse(MembershipBenefitUse use);

    /** Newest first. */
    List<MembershipBenefitUse> usesOf(String customerId, int limit);

    /** Whether the membership used a benefit since the instant (the refund rule). */
    boolean anyUseSince(UUID membershipId, Instant since);

    /** The use of a benefit on an order, if it was recorded (cashback is once per order). */
    Optional<MembershipBenefitUse> useOnOrder(String orderId, String benefit);

    void addEvent(MembershipEvent event);

    /** Oldest first. */
    List<MembershipEvent> eventsOf(UUID membershipId);

    /**
     * Claims the one trial of a customer and of a card; false when either already had one. Call inside the
     * transaction that starts the trial.
     */
    boolean claimTrial(String customerId, String cardKey, UUID membershipId, Instant at);

    /** Whether the customer ever had a membership or trial (a trial is for first-time members). */
    boolean hadMembership(String customerId);

    /** Claims the idempotency key of a grant of free days; returns the earlier grant's membership id when taken. */
    Optional<UUID> claimGrant(String key, String customerId, String tierCode, int days, String reason, String by,
                              UUID membershipId, Instant at);

    Optional<UUID> grantMembership(String key);

    /**
     * Orders delivered since the instant whose shopper held an active membership when it was delivered and that have no
     * cashback recorded yet: the sweep behind the delivery hook, so a missed hook never costs a member their cashback.
     */
    List<String> deliveredOrdersAwaitingCashback(Instant since, int limit);
}
