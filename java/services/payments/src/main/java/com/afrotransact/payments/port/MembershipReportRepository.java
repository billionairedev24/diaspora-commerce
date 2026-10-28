package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.List;

/** The figures staff and finance read: churn, what the benefits cost, what the fees brought in. Read-only. */
public interface MembershipReportRepository {

    /** Members that joined (or started a trial) in the period, per tier. */
    List<TierFlow> flowsByTier(Instant from, Instant to);

    /** Live members at the start of the period, per tier (the churn denominator). */
    List<TierStart> liveAtStart(Instant at);

    /** Cost of the benefits used in the period per benefit; uses of orders that never went through are left out. */
    List<BenefitCost> benefitCosts(Instant from, Instant to);

    /** Fees charged and refunded in the period, per tier. */
    List<TierRevenue> revenue(Instant from, Instant to);

    /**
     * @param joined         memberships started
     * @param canceled       ended because the member canceled
     * @param lapsed         ended because renewals kept failing
     * @param refunded       ended by a refund
     * @param expired        complimentary days ran out
     */
    record TierFlow(String tierCode, long joined, long canceled, long lapsed, long refunded, long expired) {
    }

    record TierStart(String tierCode, long members) {
    }

    record BenefitCost(String benefit, long uses, long amountMinor) {
    }

    record TierRevenue(String tierCode, long chargedMinor, long refundedMinor, long charges) {
    }
}
