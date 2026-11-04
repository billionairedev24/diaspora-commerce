package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Referral;
import com.afrotransact.payments.domain.ReferralRewardGrant;
import com.afrotransact.payments.domain.ReferralStatus;

/** Referrals, their rewards and the customers' referral codes. */
public interface ReferralRepository {

    // ---- codes

    Optional<String> codeOf(String customerId);

    Optional<String> customerOfCode(String code);

    /** Stores the code unless the customer already has one or the code is taken; true when stored. */
    boolean insertCode(String customerId, String code, Instant now);

    // ---- referrals

    /** Stores a new referral; false when the friend already has one (a customer is referred once). */
    boolean insert(Referral referral);

    Optional<Referral> find(UUID id);

    Optional<Referral> lock(UUID id);

    Optional<Referral> findByReferred(String referredId);

    /** Saves the next state (optimistic version); returns the stored referral. */
    Referral update(Referral referral);

    /** Newest first. */
    List<Referral> ofReferrer(String referrerId, int limit);

    /** Newest first; null filters match everything, {@code customerId} matches either side. */
    List<Referral> search(ReferralStatus status, String customerId, int limit);

    /** Referrals whose rewards are due at {@code now}, oldest due first. */
    List<UUID> due(Instant now, int limit);

    /** Pending referrals past their validity window. */
    List<UUID> expiring(Instant now, int limit);

    // ---- rewards

    void saveRewards(List<ReferralRewardGrant> rewards);

    void updateReward(ReferralRewardGrant reward);

    List<ReferralRewardGrant> rewards(UUID referralId);

    Map<UUID, List<ReferralRewardGrant>> rewardsOf(Collection<UUID> referralIds);

    /** Referral credit granted to the customer so far (either side). */
    long creditGrantedTo(String customerId);

    /** Referrals of the referrer that were rewarded on their side. */
    int rewardedCount(String referrerId);

    // ---- figures

    /** What a referrer sees: invited, still open, rewarded, earned. */
    Earnings earnings(String customerId);

    /** The program's cost and counts, for staff. */
    Totals totals();

    /**
     * @param earnedMinor        credit granted to the customer by referrals, either side
     * @param membershipDays     membership days granted by referrals
     * @param pendingMinor       credit promised and not granted yet (qualified referrals waiting)
     */
    record Earnings(int invited, int open, int rewarded, long earnedMinor, int membershipDays, long pendingMinor) {
    }

    /**
     * @param costMinor          credit granted by the program in all (the platform's cost)
     * @param membershipDays     membership days granted in all
     * @param pendingMinor       credit promised to qualified referrals and not granted yet
     * @param byStatus           referrals per status
     */
    record Totals(long costMinor, int membershipDays, long pendingMinor, Map<ReferralStatus, Integer> byStatus) {

        public Totals {
            byStatus = Map.copyOf(byStatus);
        }
    }
}
