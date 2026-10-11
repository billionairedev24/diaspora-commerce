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
 */
public record RewardRule(UUID id, String name, String description, RewardRuleStatus status, OrgKind kind,
                         RewardMetric metric, long threshold, Integer windowDays, Integer withinDaysOfApproval,
                         RewardType rewardType, Integer freeDays, Long creditCents, BigDecimal percentOff,
                         Integer durationInvoices, List<String> eligiblePlans, Instant startsAt, Instant endsAt,
                         Integer maxGrants, int grantsCount, String createdBy, Instant createdAt, String updatedBy,
                         Instant updatedAt, long version) {

    public RewardRule {
        eligiblePlans = eligiblePlans == null ? List.of() : List.copyOf(eligiblePlans);
    }

    /** "1 month free", "$25 credit", "20% off your next 3 invoices". */
    public String rewardLabel() {
        return RewardLabels.reward(rewardType, freeDays, creditCents, percentOff, durationInvoices);
    }

    /** "$1,000 in sales within 60 days of approval". */
    public String conditionLabel() {
        StringBuilder s = new StringBuilder(metric.format(threshold)).append(' ').append(metric.noun());
        if (windowDays != null) {
            s.append(" in ").append(windowDays).append(" days");
        }
        if (withinDaysOfApproval != null) {
            s.append(" within ").append(withinDaysOfApproval).append(" days of approval");
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
                rewardType, freeDays, creditCents, percentOff, durationInvoices, eligiblePlans, startsAt, endsAt,
                maxGrants, grantsCount, createdBy, createdAt, actor, now, version);
    }
}
