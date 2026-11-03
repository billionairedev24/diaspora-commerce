package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.payments.domain.BenefitCode;

/**
 * What staff and finance read about memberships (S323): members per tier, churn, what the benefits cost, recurring
 * revenue and the credit the membership granted (payments:read).
 */
public interface MembershipReportService {

    /** The report for [from, to). */
    Report report(Instant from, Instant to);

    /**
     * @param liveMembers    members now (trialling, active, past due)
     * @param mrrMinor       monthly recurring revenue: paying members' price per month (yearly fees divided by twelve)
     * @param arrMinor       {@code mrrMinor} times twelve
     * @param chargedMinor   fees charged in the period (refunded ones included)
     * @param refundedMinor  fees refunded in the period
     * @param netRevenueMinor charged less refunded
     * @param benefitCostMinor what the benefits cost in the period: fees waived plus credit granted
     * @param creditGrantedMinor credit granted through memberships (cashback and monthly credit)
     * @param feesWaivedMinor delivery and shipping fees waived
     * @param churnBps       members who ended (canceled, lapsed, refunded) over members at the start, in hundredths of
     *                       a percent
     */
    record Report(Instant from, Instant to, String currency, long liveMembers, long trialing, long mrrMinor,
                  long arrMinor, long chargedMinor, long refundedMinor, long netRevenueMinor, long benefitCostMinor,
                  long creditGrantedMinor, long feesWaivedMinor, long membersAtStart, long joined, long ended,
                  long lapsed, long canceled, long refunded, int churnBps, List<TierFigures> tiers,
                  List<BenefitCostLine> costs) {
    }

    /** One tier's figures. */
    record TierFigures(String tierCode, String tierName, String status, long members, long trialing, long active,
                       long pastDue, long cancelling, long mrrMinor, long joined, long canceled, long lapsed,
                       long refunded, long membersAtStart, int churnBps, long chargedMinor, long refundedMinor) {
    }

    record BenefitCostLine(BenefitCode benefit, long uses, long amountMinor) {
    }
}
