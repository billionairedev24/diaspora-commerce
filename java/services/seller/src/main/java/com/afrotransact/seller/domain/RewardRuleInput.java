package com.afrotransact.seller.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Staff input for a reward rule. {@code comparison} (default AT_LEAST), {@code windowAnchor} (default APPROVAL),
 * {@code checkAtWindowEnd} (default false; always true for LESS_THAN) and {@code checkLeadHours} (default 24) are S299.
 */
public record RewardRuleInput(String name, String description, OrgKind kind, RewardMetric metric, Long threshold,
                              Integer windowDays, Integer withinDaysOfApproval, RewardComparison comparison,
                              RewardWindowAnchor windowAnchor, Boolean checkAtWindowEnd, Integer checkLeadHours,
                              RewardType rewardType, Integer freeDays, Long creditCents, BigDecimal percentOff,
                              Integer durationInvoices, List<String> eligiblePlans, Instant startsAt, Instant endsAt,
                              Integer maxGrants, RewardRuleStatus status) {

    public RewardRuleInput {
        eligiblePlans = eligiblePlans == null ? List.of() : List.copyOf(eligiblePlans);
    }

    /** An AT_LEAST rule anchored at approval, granted as soon as it is reached. */
    public RewardRuleInput(String name, String description, OrgKind kind, RewardMetric metric, Long threshold,
                           Integer windowDays, Integer withinDaysOfApproval, RewardType rewardType, Integer freeDays,
                           Long creditCents, BigDecimal percentOff, Integer durationInvoices,
                           List<String> eligiblePlans, Instant startsAt, Instant endsAt, Integer maxGrants,
                           RewardRuleStatus status) {
        this(name, description, kind, metric, threshold, windowDays, withinDaysOfApproval, null, null, null, null,
                rewardType, freeDays, creditCents, percentOff, durationInvoices, eligiblePlans, startsAt, endsAt,
                maxGrants, status);
    }

    public RewardComparison effectiveComparison() {
        return comparison == null ? RewardComparison.AT_LEAST : comparison;
    }

    public RewardWindowAnchor effectiveAnchor() {
        return windowAnchor == null ? RewardWindowAnchor.APPROVAL : windowAnchor;
    }

    /** LESS_THAN rules are always decided at window end. */
    public boolean effectiveCheckAtWindowEnd() {
        return effectiveComparison() == RewardComparison.LESS_THAN || Boolean.TRUE.equals(checkAtWindowEnd);
    }

    public int effectiveCheckLeadHours() {
        return checkLeadHours == null ? RewardRule.DEFAULT_CHECK_LEAD_HOURS : checkLeadHours;
    }

    /** 422 seller.invalid-reward-rule with every field error; only the reward type's own fields are kept. */
    public void validate() {
        Map<String, String> e = new LinkedHashMap<>();
        if (name == null || name.isBlank() || name.length() > 120) {
            e.put("name", "A name of up to 120 characters");
        }
        if (metric == null) {
            e.put("metric", "Choose what to measure");
        }
        if (threshold == null || threshold < 1) {
            e.put("threshold", "At least 1");
        }
        if (windowDays != null && (windowDays < 1 || windowDays > 3650)) {
            e.put("windowDays", "1 to 3650 days");
        }
        if (withinDaysOfApproval != null && (withinDaysOfApproval < 1 || withinDaysOfApproval > 3650)) {
            e.put("withinDaysOfApproval", "1 to 3650 days");
        }
        if (checkLeadHours != null && (checkLeadHours < 0 || checkLeadHours > 168)) {
            e.put("checkLeadHours", "0 to 168 hours");
        }
        if (effectiveCheckAtWindowEnd()) {
            String why = effectiveComparison() == RewardComparison.LESS_THAN ? "A \"less than\" rule"
                    : "A rule checked at window end";
            if (withinDaysOfApproval == null) {
                e.putIfAbsent("withinDaysOfApproval", why + " needs a window: the first N days");
            }
            if (windowDays != null) {
                e.put("windowDays", why + " counts the whole window: leave the rolling window empty");
            }
            if (metric != null && !metric.summed()) {
                e.put("metric", why + " needs a summed metric (sales, completed orders or bookings)");
            }
        }
        if (rewardType == null) {
            e.put("rewardType", "Choose a reward");
        } else {
            switch (rewardType) {
                case FREE_DAYS -> {
                    if (freeDays == null || freeDays < 1 || freeDays > 365) {
                        e.put("freeDays", "1 to 365 days");
                    }
                }
                case CREDIT -> {
                    if (creditCents == null || creditCents < 1 || creditCents > 1_000_000) {
                        e.put("creditCents", "$0.01 to $10,000");
                    }
                }
                case PERCENT_OFF -> {
                    if (percentOff == null || percentOff.signum() <= 0 || percentOff.compareTo(BigDecimal.valueOf(100))
                            > 0 || percentOff.scale() > 2) {
                        e.put("percentOff", "0.01 to 100");
                    }
                    if (durationInvoices == null || durationInvoices < 1 || durationInvoices > 36) {
                        e.put("durationInvoices", "1 to 36 invoices");
                    }
                }
                default -> {
                }
            }
        }
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            e.put("endsAt", "Ends after it starts");
        }
        if (maxGrants != null && maxGrants < 1) {
            e.put("maxGrants", "At least 1");
        }
        if (!e.isEmpty()) {
            throw new SellerRuleViolation("seller.invalid-reward-rule", "Check the rule: "
                    + String.join(", ", e.keySet()), e);
        }
    }

    public Integer rewardFreeDays() {
        return rewardType == RewardType.FREE_DAYS ? freeDays : null;
    }

    public Long rewardCreditCents() {
        return rewardType == RewardType.CREDIT ? creditCents : null;
    }

    public BigDecimal rewardPercentOff() {
        return rewardType == RewardType.PERCENT_OFF ? percentOff : null;
    }

    public Integer rewardDurationInvoices() {
        return rewardType == RewardType.PERCENT_OFF ? durationInvoices : null;
    }
}
