package com.afrotransact.seller.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.afrotransact.seller.application.RewardPreview;
import com.afrotransact.seller.application.RewardService;
import com.afrotransact.seller.domain.BillingGrant;
import com.afrotransact.seller.domain.RewardComparison;
import com.afrotransact.seller.domain.RewardMetric;
import com.afrotransact.seller.domain.RewardProgress;
import com.afrotransact.seller.domain.RewardRule;
import com.afrotransact.seller.domain.RewardRuleInput;
import com.afrotransact.seller.domain.RewardRuleStatus;
import com.afrotransact.seller.domain.RewardType;
import com.afrotransact.seller.domain.RewardWindowAnchor;
import com.afrotransact.seller.port.BillingGrantRepository;
import com.afrotransact.seller.port.Page;
import io.swagger.v3.oas.annotations.media.Schema;

/** Reward rules, previews, the grants log, seller progress and staff adjustments. */
final class RewardDtos {

    private RewardDtos() {
    }

    @Schema(name = "RewardRuleRequest", description = "Reach threshold of metric (optionally in the last windowDays "
            + "days and/or within withinDaysOfApproval days of approval) and get the reward, once per seller. "
            + "LESS_THAN rewards staying under the threshold in the first withinDaysOfApproval days (from approval or "
            + "the subscription's start), checked once, checkLeadHours before the window or the trial ends")
    record RuleBody(String name, String description,
                    @Schema(allowableValues = {"SELLER", "PROVIDER"}) String kind,
                    @Schema(allowableValues = {"GROSS_SALES", "ACTIVE_LISTINGS", "COMPLETED_ORDERS",
                        "COMPLETED_BOOKINGS", "DAYS_SINCE_APPROVAL"}) String metric,
                    @Schema(description = "Cents for GROSS_SALES, a count otherwise") Long threshold,
                    Integer windowDays,
                    @Schema(description = "The first N days from windowAnchor (the window of a rule checked at "
                            + "window end)") Integer withinDaysOfApproval,
                    @Schema(allowableValues = {"AT_LEAST", "LESS_THAN"}, description = "Default AT_LEAST")
                    String comparison,
                    @Schema(allowableValues = {"APPROVAL", "SUBSCRIPTION_START"}, description = "Default APPROVAL")
                    String windowAnchor,
                    @Schema(description = "AT_LEAST: decide once when the window closes (LESS_THAN always does)")
                    Boolean checkAtWindowEnd,
                    @Schema(description = "Hours before the window (or the trial) ends that the check runs; "
                            + "default 24") Integer checkLeadHours,
                    @Schema(allowableValues = {"FREE_DAYS", "CREDIT", "PERCENT_OFF"}) String rewardType,
                    Integer freeDays, Long creditCents, BigDecimal percentOff, Integer durationInvoices,
                    List<String> eligiblePlans, Instant startsAt, Instant endsAt, Integer maxGrants,
                    @Schema(allowableValues = {"DRAFT", "ACTIVE", "PAUSED", "ENDED"}) String status,
                    Long expectedVersion, @Schema(description = "Preview only: marks who already got this rule")
                    String ruleId) {

        RewardRuleInput toInput() {
            return new RewardRuleInput(name, description, kind == null ? null : Inputs.kind(kind),
                    Inputs.enumOf(RewardMetric.class, metric, "metric"), threshold, windowDays, withinDaysOfApproval,
                    Inputs.enumOf(RewardComparison.class, comparison, "comparison"),
                    Inputs.enumOf(RewardWindowAnchor.class, windowAnchor, "windowAnchor"), checkAtWindowEnd,
                    checkLeadHours, Inputs.enumOf(RewardType.class, rewardType, "rewardType"), freeDays, creditCents, percentOff,
                    durationInvoices, eligiblePlans, startsAt, endsAt, maxGrants,
                    Inputs.enumOf(RewardRuleStatus.class, status, "status"));
        }
    }

    @Schema(name = "RewardRule")
    record RuleResponse(String id, String name, String description, String status, String kind, String metric,
                        long threshold, Integer windowDays, Integer withinDaysOfApproval,
                        @Schema(allowableValues = {"AT_LEAST", "LESS_THAN"}) String comparison,
                        @Schema(allowableValues = {"APPROVAL", "SUBSCRIPTION_START"}) String windowAnchor,
                        @Schema(description = "Decided once when the window closes (always for LESS_THAN)")
                        boolean checkAtWindowEnd, int checkLeadHours, String rewardType,
                        Integer freeDays, Long creditCents, BigDecimal percentOff, Integer durationInvoices,
                        List<String> eligiblePlans, Instant startsAt, Instant endsAt, Integer maxGrants,
                        int grantsCount, String rewardLabel, String conditionLabel, String createdBy,
                        Instant createdAt, String updatedBy, Instant updatedAt, long version) {

        static RuleResponse of(RewardRule r) {
            return new RuleResponse(r.id().toString(), r.name(), r.description(), r.status().name(),
                    r.kind() == null ? null : r.kind().name(), r.metric().name(), r.threshold(), r.windowDays(),
                    r.withinDaysOfApproval(), r.comparison().name(), r.windowAnchor().name(), r.checkedAtWindowEnd(),
                    r.checkLeadHours(), r.rewardType().name(), r.freeDays(), r.creditCents(), r.percentOff(),
                    r.durationInvoices(), r.eligiblePlans(), r.startsAt(), r.endsAt(), r.maxGrants(), r.grantsCount(),
                    r.rewardLabel(), r.conditionLabel(), r.createdBy(), r.createdAt(), r.updatedBy(), r.updatedAt(),
                    r.version());
        }
    }

    @Schema(name = "RewardPreview", description = "Who qualifies now (best first, at most 500 rows)")
    record PreviewResponse(int evaluated, int qualifying,
                           @Schema(description = "Rules checked at window end: organisations still in their window")
                           int pending, List<PreviewRow> rows) {

        static PreviewResponse of(RewardPreview p) {
            return new PreviewResponse(p.evaluated(), p.qualifying(), p.pending(), p.rows().stream().map(r ->
                    new PreviewRow(r.orgId(), r.orgName(), r.planCode(), r.value(), r.threshold(), r.percent(),
                            r.qualifies(), r.alreadyGranted(), r.reason(), r.status(), r.checkAt())).toList());
        }
    }

    @Schema(name = "RewardPreviewRow")
    record PreviewRow(String orgId, String orgName, String planCode,
                      @Schema(description = "The metric so far (in the window)") long value, long threshold,
                      int percent, boolean qualifies, boolean alreadyGranted, String reason,
                      @Schema(allowableValues = {"GRANTED", "QUALIFIES", "PENDING", "CHECKED", "NOT_YET",
                          "INELIGIBLE"}, description = "PENDING: in the window of a rule checked at window end")
                      String status,
                      @Schema(description = "Rules checked at window end: when this organisation is checked")
                      Instant checkAt) {
    }

    @Schema(name = "RewardProgress", description = "A reward the organisation can still earn")
    record ProgressView(String ruleId, String name, String description, String metric, long value, long threshold,
                        int percent, boolean qualifies, String label, String rewardLabel, String conditionLabel,
                        Instant endsAt,
                        @Schema(allowableValues = {"AT_LEAST", "LESS_THAN"}) String comparison,
                        @Schema(allowableValues = {"APPROVAL", "SUBSCRIPTION_START"}) String windowAnchor,
                        @Schema(description = "The window's length in days (the first N days)") Integer windowDays,
                        @Schema(description = "Checked once at window end and the check is still to come")
                        boolean pending,
                        @Schema(description = "Rules checked at window end: when the check runs") Instant checkAt,
                        @Schema(description = "Rules checked at window end: when the window closes")
                        Instant windowClosesAt) {

        static ProgressView of(RewardProgress p) {
            RewardRule r = p.rule();
            return new ProgressView(r.id().toString(), r.name(), r.description(), r.metric().name(), p.value(),
                    r.threshold(), p.percent(), p.qualifies(), p.label(), r.rewardLabel(), r.conditionLabel(),
                    r.endsAt(), r.comparison().name(), r.windowAnchor().name(), r.withinDaysOfApproval(), p.pending(),
                    p.checkAt(), p.closesAt());
        }
    }

    @Schema(name = "BillingGrant", description = "A reward or staff adjustment and the Stripe objects it produced")
    record GrantView(String id, String orgId,
                     @Schema(allowableValues = {"RULE", "MANUAL"}) String source, String ruleId,
                     @Schema(allowableValues = {"FREE_DAYS", "CREDIT", "PERCENT_OFF", "WAIVE_INVOICE", "TRIAL_END"})
                     String type, Long creditCents, Integer freeDays, BigDecimal percentOff, Integer durationInvoices,
                     String invoiceId, @Schema(allowableValues = {"PENDING", "APPLIED", "FAILED"}) String status,
                     String reasonCode, String note, String summary, String actorId, String metric, Long metricValue,
                     String stripeCustomerId, String stripeSubscriptionId, String stripeBalanceTransactionId,
                     String stripeCouponId, Instant previousTrialEnd, Instant newTrialEnd, int attempts,
                     String lastError, Instant createdAt, Instant appliedAt,
                     @Schema(allowableValues = {"AT_LEAST", "LESS_THAN"}, description = "Seller rewards: the rule's "
                             + "comparison (LESS_THAN = low-sales relief); null for staff adjustments and in the "
                             + "grants log") String ruleComparison) {

        static GrantView of(BillingGrant g) {
            return of(g, null);
        }

        static GrantView ofEarned(RewardService.Earned e) {
            return of(e.grant(), e.comparison());
        }

        static GrantView of(BillingGrant g, RewardComparison comparison) {
            BillingGrant.StripeRefs s = g.stripe();
            return new GrantView(g.id().toString(), g.orgId(), g.source().name(),
                    g.ruleId() == null ? null : g.ruleId().toString(), g.type().name(), g.creditCents(), g.freeDays(),
                    g.percentOff(), g.durationInvoices(), g.invoiceId(), g.status().name(), g.reasonCode(), g.note(),
                    g.summary(), g.actorId(), g.metric() == null ? null : g.metric().name(), g.metricValue(),
                    s.customerId(), s.subscriptionId(), s.balanceTransactionId(), s.couponId(), s.previousTrialEnd(),
                    s.newTrialEnd(), g.attempts(), g.lastError(), g.createdAt(), g.appliedAt(),
                    comparison == null ? null : comparison.name());
        }
    }

    @Schema(name = "BillingGrantPage")
    record GrantPage(List<GrantView> items, int page, int size, long total) {

        static GrantPage of(Page<BillingGrant> p) {
            return new GrantPage(p.items().stream().map(GrantView::of).toList(), p.page(), p.size(), p.total());
        }
    }

    @Schema(name = "SellerRewards", description = "Rewards the organisation can still earn and what it received")
    record SellerRewardsResponse(List<ProgressView> available, List<GrantView> earned) {
    }

    @Schema(name = "BillingReasonCode")
    record ReasonView(String code, String label) {

        static ReasonView of(BillingGrantRepository.ReasonCode r) {
            return new ReasonView(r.code(), r.label());
        }
    }

    @Schema(name = "CreditRequest")
    record CreditBody(Long amountCents, String reasonCode, String note) {
    }

    @Schema(name = "FreeDaysRequest")
    record DaysBody(Integer days, String reasonCode, String note) {
    }

    @Schema(name = "WaiveInvoiceRequest")
    record WaiveBody(String reasonCode, String note) {
    }
}
