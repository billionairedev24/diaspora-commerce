package com.afrotransact.seller.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "Reach {@code threshold} of {@code metric} (in the last {@code windowDays} days, and/or within
 * {@code withinDaysOfApproval} days of approval) and get {@code rewardType}". Granted at most once per seller and at
 * most {@code maxGrants} times overall, between {@code startsAt} and {@code endsAt}, on {@code eligiblePlans} (empty =
 * every paid plan) and for {@code kind} (null = sellers and providers).
 *
 * <p>S299: {@code comparison} LESS_THAN rewards staying under the threshold ("less than $1,000 in the first 30 days");
 * {@code withinDaysOfApproval} is then the window's length, counted from {@code windowAnchor} (approval or the
 * subscription's start). Such a rule, and an AT_LEAST rule with {@code checkAtWindowEnd}, is decided once:
 * {@code checkLeadHours} before its window closes (or before the renewal when the window closes with the trial or the
 * billing period), so a free period moves {@code trial_end} before Stripe creates the invoice.
 */
public record RewardRule(UUID id, String name, String description, RewardRuleStatus status, OrgKind kind,
                         RewardMetric metric, long threshold, Integer windowDays, Integer withinDaysOfApproval,
                         RewardComparison comparison, RewardWindowAnchor windowAnchor, boolean checkAtWindowEnd,
                         int checkLeadHours, RewardType rewardType, Integer freeDays, Long creditCents, BigDecimal percentOff,
                         Integer durationInvoices, List<String> eligiblePlans, Instant startsAt, Instant endsAt,
                         Integer maxGrants, int grantsCount, String createdBy, Instant createdAt, String updatedBy,
                         Instant updatedAt, long version) {

    /** Default lead of a window-end check: a day before the window (or the trial) ends. */
    public static final int DEFAULT_CHECK_LEAD_HOURS = 24;

    public RewardRule {
        eligiblePlans = eligiblePlans == null ? List.of() : List.copyOf(eligiblePlans);
        comparison = comparison == null ? RewardComparison.AT_LEAST : comparison;
        windowAnchor = windowAnchor == null ? RewardWindowAnchor.APPROVAL : windowAnchor;
    }

    /** An AT_LEAST rule anchored at approval and granted as soon as it is reached (the S296 rules). */
    public RewardRule(UUID id, String name, String description, RewardRuleStatus status, OrgKind kind,
                      RewardMetric metric, long threshold, Integer windowDays, Integer withinDaysOfApproval,
                      RewardType rewardType, Integer freeDays, Long creditCents, BigDecimal percentOff,
                      Integer durationInvoices, List<String> eligiblePlans, Instant startsAt, Instant endsAt,
                      Integer maxGrants, int grantsCount, String createdBy, Instant createdAt, String updatedBy,
                      Instant updatedAt, long version) {
        this(id, name, description, status, kind, metric, threshold, windowDays, withinDaysOfApproval,
                RewardComparison.AT_LEAST, RewardWindowAnchor.APPROVAL, false, DEFAULT_CHECK_LEAD_HOURS, rewardType,
                freeDays, creditCents, percentOff, durationInvoices, eligiblePlans, startsAt, endsAt, maxGrants,
                grantsCount, createdBy, createdAt, updatedBy, updatedAt, version);
    }

    /** Decided once, when the window closes (LESS_THAN always; AT_LEAST when asked). */
    public boolean checkedAtWindowEnd() {
        return comparison == RewardComparison.LESS_THAN || checkAtWindowEnd;
    }

    /** "1 month free", "$25 credit", "20% off your next 3 invoices". */
    public String rewardLabel() {
        return RewardLabels.reward(rewardType, freeDays, creditCents, percentOff, durationInvoices);
    }

    /**
     * "$1,000 in sales within 60 days of approval"; "less than $1,000 in sales in the first 30 days of the
     * subscription".
     */
    public String conditionLabel() {
        StringBuilder s = new StringBuilder();
        if (comparison == RewardComparison.LESS_THAN) {
            s.append("less than ");
        }
        s.append(metric.format(threshold)).append(' ').append(metric.noun());
        if (windowDays != null) {
            s.append(" in ").append(windowDays).append(" days");
        }
        if (withinDaysOfApproval != null) {
            String of = windowAnchor == RewardWindowAnchor.SUBSCRIPTION_START ? "the subscription" : "approval";
            if (checkedAtWindowEnd()) {
                s.append(" in the first ").append(withinDaysOfApproval).append(" days of ").append(of);
            } else {
                s.append(" within ").append(withinDaysOfApproval).append(" days of ").append(of);
            }
        }
        return s.toString();
    }

    public boolean isLive(Instant now) {
        return status == RewardRuleStatus.ACTIVE && (startsAt == null || !now.isBefore(startsAt))
                && (endsAt == null || now.isBefore(endsAt));
    }

    public boolean exhausted() {
        return maxGrants != null && grantsCount >= maxGrants;
    }

    public RewardRule withStatus(RewardRuleStatus s, String actor, Instant now) {
        return new RewardRule(id, name, description, s, kind, metric, threshold, windowDays, withinDaysOfApproval,
                comparison, windowAnchor, checkAtWindowEnd, checkLeadHours, rewardType, freeDays, creditCents,
                percentOff, durationInvoices, eligiblePlans, startsAt, endsAt, maxGrants, grantsCount, createdBy,
                createdAt, actor, now, version);
    }
}
