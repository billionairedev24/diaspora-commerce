package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Membership;
import com.afrotransact.payments.domain.MembershipStatus;

/** Shoppers' memberships. At most one is live per customer. */
public interface MembershipRepository {

    Optional<Membership> find(UUID id);

    /** The customer's trialling, active or past-due membership. */
    Optional<Membership> liveOf(String customerId);

    /** The same, locked until the transaction ends. */
    Optional<Membership> lockLiveOf(String customerId);

    Optional<Membership> lock(UUID id);

    /** Newest first. */
    List<Membership> historyOf(String customerId, int limit);

    void insert(Membership membership);

    /** Saves the membership; fails with an optimistic-lock error when the version moved. Returns the stored row. */
    Membership update(Membership membership);

    /**
     * Ids of live memberships whose period ended and that are due a renewal, a retry, a cancellation to apply or the
     * end of their free days, oldest first. Not locked: the worker locks and re-checks each one in its own transaction.
     */
    List<UUID> dueIds(Instant now, int limit);

    /** Active paid or complimentary memberships whose tier may grant monthly credit (the service filters further). */
    List<Membership> liveForMonthlyCredit(int limit, UUID after);

    /** Staff list: filter by tier and status; newest first; {@code before} pages backwards. */
    List<Membership> list(String tierCode, MembershipStatus status, Instant before, int limit);

    /** Live memberships per tier code and status, for the staff overview. */
    List<TierCount> countsByTier();

    /** Members that have the tier right now. */
    record TierCount(String tierCode, MembershipStatus status, boolean cancelling, long count, long priceMinorSum,
                     long monthlyEquivalentMinor) {
    }
}
