package com.afrotransact.payments.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.application.MembershipBenefitsService.Entitlements;
import com.afrotransact.payments.application.MembershipBenefitsService.Upsell;
import com.afrotransact.payments.application.MembershipBenefitsService.WaiverQuote;
import com.afrotransact.payments.application.MembershipGrants.Grant;
import com.afrotransact.payments.application.MembershipReportService.BenefitCostLine;
import com.afrotransact.payments.application.MembershipReportService.Report;
import com.afrotransact.payments.application.MembershipReportService.TierFigures;
import com.afrotransact.payments.application.MembershipService.BenefitUsage;
import com.afrotransact.payments.application.MembershipService.Card;
import com.afrotransact.payments.application.MembershipService.MemberView;
import com.afrotransact.payments.application.MembershipService.RefundRule;
import com.afrotransact.payments.application.MembershipTierService.TierOverview;
import com.afrotransact.payments.domain.BenefitCode;
import com.afrotransact.payments.domain.BillingInterval;
import com.afrotransact.payments.domain.FeeWaivers;
import com.afrotransact.payments.domain.Membership;
import com.afrotransact.payments.domain.MembershipBenefitUse;
import com.afrotransact.payments.domain.MembershipCharge;
import com.afrotransact.payments.domain.MembershipEvent;
import com.afrotransact.payments.domain.MembershipSettings;
import com.afrotransact.payments.domain.MembershipTier;
import com.afrotransact.payments.domain.TierBenefit;
import com.afrotransact.payments.domain.TierStatus;
import com.afrotransact.payments.port.MembershipSettingsRepository;
import com.afrotransact.payments.port.MembershipTierRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request and response bodies of the membership API (S323). Money is in minor units of the tier's currency. */
final class MembershipDtos {

    private MembershipDtos() {
    }

    // ------------------------------------------------------------------ tiers

    @Schema(name = "MembershipBenefit", description = "One benefit of a tier; which numbers apply depends on the code: "
            + "FREE_DELIVERY and FREE_SHIPPING take minOrderMinor and capMinor, CASHBACK takes percentBps (and "
            + "optionally minOrderMinor and capMinor), EARLY_ACCESS takes hours, BOOSTED_REWARDS takes percentBps, "
            + "MONTHLY_CREDIT takes amountMinor; MEMBER_PRICES and PRIORITY_SUPPORT take none")
    record BenefitDto(
            @NotNull BenefitCode code,
            @PositiveOrZero @Schema(description = "Goods subtotal the order needs for the benefit") Long minOrderMinor,
            @Min(1) @Schema(description = "Most the benefit is worth on one order; absent for no cap") Long capMinor,
            @Min(1) @Max(10000) @Schema(description = "Hundredths of a percent (200 = 2%)") Integer percentBps,
            @Min(1) @Max(720) @Schema(description = "Hours before the public opening") Integer hours,
            @Min(1) @Schema(description = "Credit granted each month") Long amountMinor) {

        static BenefitDto of(TierBenefit b) {
            return new BenefitDto(b.code(), b.minOrderMinor(), b.capMinor(), b.percentBps(), b.hours(),
                    b.amountMinor());
        }

        TierBenefit toDomain() {
            return new TierBenefit(code, minOrderMinor, capMinor, percentBps, hours, amountMinor);
        }
    }

    @Schema(name = "MembershipTier", description = "A membership tier staff define in Settings. Members keep the price "
            + "they signed up at; benefit changes apply to every member at once.")
    record TierDto(String code, String name, String description,
                   @Schema(allowableValues = {"DRAFT", "PUBLISHED", "RETIRED"}) String status,
                   @Schema(description = "Lower is the entry tier; a higher order is an upgrade") int sortOrder,
                   String currency, long monthlyPriceMinor, long yearlyPriceMinor, int trialDays,
                   List<BenefitDto> benefits, Instant updatedAt, String updatedBy, long version) {

        static TierDto of(MembershipTier t) {
            return t == null ? null : new TierDto(t.code(), t.name(), t.description(), t.status().name(),
                    t.sortOrder(), t.currency(), t.monthlyPriceMinor(), t.yearlyPriceMinor(), t.trialDays(),
                    t.benefits().stream().map(BenefitDto::of).toList(), t.updatedAt(), t.updatedBy(), t.version());
        }
    }

    @Schema(name = "MembershipTierOverview", description = "A tier with its members (staff)")
    record TierOverviewDto(TierDto tier, long members, long trialing) {

        static TierOverviewDto of(TierOverview o) {
            return new TierOverviewDto(TierDto.of(o.tier()), o.members(), o.trialing());
        }
    }

    @Schema(name = "MembershipTierRequest", description = "Create or change a tier. A change needs the version the "
            + "editor loaded and a reason (at least 15 characters); the code is fixed once created.")
    record TierRequest(
            @NotBlank @Size(max = 40) String code,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 500) String description,
            @NotNull TierStatus status,
            int sortOrder,
            @NotNull @Min(1) Long monthlyPriceMinor,
            @NotNull @Min(1) Long yearlyPriceMinor,
            @Min(0) @Max(90) int trialDays,
            @NotNull @Size(max = 12) List<@Valid BenefitDto> benefits,
            @NotBlank @Size(max = 500) String reason,
            @Schema(description = "Required when changing a tier, absent when creating") Long expectedVersion) {
    }

    @Schema(name = "MembershipTierChange", description = "One audited change of a tier")
    record TierChangeDto(UUID id, String tierCode,
                         @Schema(allowableValues = {"CREATED", "UPDATED", "PUBLISHED", "RETIRED"}) String action,
                         String changedBy, Instant changedAt, String reason, String beforeJson, String afterJson) {

        static TierChangeDto of(MembershipTierRepository.Change c) {
            return new TierChangeDto(c.id(), c.tierCode(), c.action(), c.changedBy(), c.changedAt(), c.reason(),
                    c.beforeJson(), c.afterJson());
        }
    }

    // ------------------------------------------------------------------ memberships

    @Schema(name = "Membership", description = "A shopper's membership")
    record MembershipDto(UUID id, String customerId, String tierCode,
                         @Schema(allowableValues = {"MONTHLY", "YEARLY"}) String interval,
                         @Schema(allowableValues = {"PAID", "COMPLIMENTARY"}) String source,
                         @Schema(allowableValues = {"TRIALING", "ACTIVE", "PAST_DUE", "CANCELED", "LAPSED",
                                 "EXPIRED"}) String status,
                         long priceMinor, String currency, Instant startedAt, Instant periodStart, Instant periodEnd,
                         Instant trialEnd, boolean cancelAtPeriodEnd, Instant canceledAt, Instant endedAt,
                         String endReason, String pendingTierCode, String pendingInterval, String paymentMethodId,
                         int retryCount, Instant nextRetryAt, int freeDaysGranted) {

        static MembershipDto of(Membership m) {
            return m == null ? null : new MembershipDto(m.id(), m.customerId(), m.tierCode(), m.interval().name(),
                    m.source().name(), m.status().name(), m.priceMinor(), m.currency(), m.startedAt(),
                    m.periodStart(), m.periodEnd(), m.trialEnd(), m.cancelAtPeriodEnd(), m.canceledAt(),
                    m.endedAt(), m.endReason(), m.pendingTierCode(),
                    m.pendingInterval() == null ? null : m.pendingInterval().name(), m.paymentMethodId(),
                    m.retryCount(), m.nextRetryAt(), m.freeDaysGranted());
        }
    }

    @Schema(name = "MembershipCharge", description = "One charge of the membership fee and what became of it")
    record ChargeDto(UUID id, UUID membershipId, String customerId, String tierCode,
                     @Schema(allowableValues = {"JOIN", "RENEWAL", "UPGRADE"}) String kind, int attempt,
                     long amountMinor, String currency,
                     @Schema(allowableValues = {"SUCCEEDED", "FAILED", "REFUNDED"}) String status,
                     String failureCode, String failureMessage, Instant periodStart, Instant periodEnd,
                     long refundedMinor, String refundReason, Instant refundedAt, Instant createdAt) {

        static ChargeDto of(MembershipCharge c) {
            return new ChargeDto(c.id(), c.membershipId(), c.customerId(), c.tierCode(), c.kind().name(),
                    c.attempt(), c.amount().minor(), c.amount().currency(), c.status().name(), c.failureCode(),
                    c.failureMessage(), c.periodStart(), c.periodEnd(), c.refundedMinor(), c.refundReason(),
                    c.refundedAt(), c.createdAt());
        }
    }

    @Schema(name = "MembershipBenefitUse", description = "A benefit used: a fee waived on an order, cashback or "
            + "monthly credit")
    record UseDto(UUID id, String tierCode, BenefitCode benefit, String orderId, long amountMinor, String currency,
                  String note, Instant createdAt) {

        static UseDto of(MembershipBenefitUse u) {
            return new UseDto(u.id(), u.tierCode(), u.benefit(), u.orderId(), u.amount().minor(),
                    u.amount().currency(), u.note(), u.createdAt());
        }
    }

    @Schema(name = "MembershipEvent", description = "A step in a membership's life")
    record EventDto(UUID id, String type, String tierCode, String actor, String detail, Instant occurredAt) {

        static EventDto of(MembershipEvent e) {
            return new EventDto(e.id(), e.type().name(), e.tierCode(), e.actor(), e.detail(), e.occurredAt());
        }
    }

    @Schema(name = "MembershipBenefitUsage", description = "Uses of one benefit, summed")
    record UsageDto(BenefitCode benefit, long uses, long amountMinor, String currency) {

        static UsageDto of(BenefitUsage u) {
            return new UsageDto(u.benefit(), u.uses(), u.amountMinor(), u.currency());
        }
    }

    @Schema(name = "MembershipRefundRule", description = "What the fee refund rule allows now: a full refund to the "
            + "card when canceling within the window after a charge and before any benefit was used")
    record RefundRuleDto(boolean eligible,
                         @Schema(allowableValues = {"OK", "NOT_PAID", "WINDOW_PASSED", "BENEFIT_USED", "DISABLED"})
                         String reason, long amountMinor, String currency, Instant until) {

        static RefundRuleDto of(RefundRule r) {
            return new RefundRuleDto(r.eligible(), r.reason(), r.amountMinor(), r.currency(), r.until());
        }
    }

    @Schema(name = "MembershipCard", description = "The saved card billed (display data only)")
    record CardDto(String paymentMethodId, String brand, String last4, Integer expMonth, Integer expYear) {

        static CardDto of(Card c) {
            return c == null ? null : new CardDto(c.paymentMethodId(), c.brand(), c.last4(), c.expMonth(),
                    c.expYear());
        }
    }

    @Schema(name = "MyMembership", description = "A membership as its owner sees it: the tier and its benefits, the "
            + "renewal date, charges, benefits used and the refund rule. membership is absent when the customer never "
            + "joined.")
    record MemberDto(MembershipDto membership, TierDto tier, TierDto pendingTier, List<ChargeDto> charges,
                     List<UsageDto> usage, RefundRuleDto refund, boolean trialAvailable, CardDto card,
                     int refundWindowDays) {

        static MemberDto of(MemberView v) {
            return new MemberDto(MembershipDto.of(v.membership()), TierDto.of(v.tier()), TierDto.of(v.pendingTier()),
                    v.charges().stream().map(ChargeDto::of).toList(),
                    v.usage().stream().map(UsageDto::of).toList(), RefundRuleDto.of(v.refund()), v.trialAvailable(),
                    CardDto.of(v.card()), v.refundWindowDays());
        }
    }

    @Schema(name = "MembershipJoinRequest")
    record JoinRequest(
            @NotBlank @Size(max = 40) String tierCode,
            @NotNull BillingInterval interval,
            @Size(max = 120) @Schema(description = "The saved card to bill; absent for the default card")
            String paymentMethodId) {
    }

    @Schema(name = "MembershipCancelRequest")
    record CancelRequest(@Schema(description = "Also refund the latest fee and end the membership now; allowed only "
            + "by the refund rule") boolean refund) {
    }

    // ------------------------------------------------------------------ entitlements and fee waivers

    @Schema(name = "MembershipEntitlements", description = "What a customer's membership gives right now")
    record EntitlementsDto(boolean member, String customerId, String tierCode, String tierName, String status,
                           List<BenefitDto> benefits, boolean prioritySupport,
                           boolean memberPrices, int earlyAccessHours, int rewardBoostBps) {

        static EntitlementsDto of(Entitlements e) {
            return new EntitlementsDto(e.member(), e.customerId(), e.tierCode(), e.tierName(),
                    e.status() == null ? null : e.status().name(), e.benefits().stream().map(BenefitDto::of).toList(),
                    e.prioritySupport(), e.memberPrices(), e.earlyAccessHours(),
                    e.rewardBoostBps());
        }
    }

    @Schema(name = "MembershipFee", description = "A delivery fee the fee rules produced for the order")
    record FeeDto(
            @NotBlank @Size(max = 120) @Schema(description = "The caller's id for the fee (a shipment)") String key,
            @NotNull @Schema(allowableValues = {"AFROTRANSACT_DELIVERY", "CARRIER", "PICKUP", "EXPRESS"})
            FeeWaivers.Kind kind,
            @NotNull @Min(0) Long feeMinor) {

        FeeWaivers.Fee toDomain() {
            return new FeeWaivers.Fee(key, kind, feeMinor);
        }
    }

    @Schema(name = "MembershipSavingsRequest", description = "The fees of an order, to see which the membership "
            + "waives")
    record SavingsRequest(
            @NotNull @Min(0) @Schema(description = "Goods subtotal of the whole order") Long itemsMinor,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull @Size(max = 50) List<@Valid FeeDto> fees) {
    }

    @Schema(name = "MembershipWaiversRequest", description = "The fees of a customer's order (checkout, "
            + "payments:service)")
    record WaiversRequest(
            @NotBlank @Size(max = 120) String customerId,
            @NotNull @Min(0) Long itemsMinor,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull @Size(max = 50) List<@Valid FeeDto> fees) {
    }

    @Schema(name = "MembershipWaiver", description = "A fee waived because of the membership")
    record WaiverDto(String key, @Schema(allowableValues = {"FREE_DELIVERY", "FREE_SHIPPING"}) BenefitCode benefit,
                     long waivedMinor) {

        static WaiverDto of(FeeWaivers.Waiver w) {
            return new WaiverDto(w.key(), w.benefit(), w.waivedMinor());
        }
    }

    @Schema(name = "MembershipUpsell", description = "What joining would save on this order: 'Join Plus to save $X'")
    record UpsellDto(String tierCode, String tierName, long monthlyPriceMinor, long yearlyPriceMinor, int trialDays,
                     long savingMinor, String currency) {

        static UpsellDto of(Upsell u) {
            return u == null ? null : new UpsellDto(u.tierCode(), u.tierName(), u.monthlyPriceMinor(),
                    u.yearlyPriceMinor(), u.trialDays(), u.savingMinor(), u.currency());
        }
    }

    @Schema(name = "MembershipWaiverQuote", description = "The fees the membership waives on an order; upsell is "
            + "present for a shopper who is not a member and would save by joining")
    record WaiverQuoteDto(boolean member, String tierCode, String tierName, List<WaiverDto> waivers,
                          long totalWaivedMinor,
                          @Schema(description = "The credit the member earns on this order once it is delivered; 0 "
                                  + "without cashback, under its minimum or during a trial") long cashbackMinor,
                          UpsellDto upsell) {

        static WaiverQuoteDto of(WaiverQuote q) {
            return new WaiverQuoteDto(q.member(), q.tierCode(), q.tierName(),
                    q.waivers().stream().map(WaiverDto::of).toList(), q.totalWaivedMinor(), q.cashbackMinor(),
                    UpsellDto.of(q.upsell()));
        }
    }

    // ------------------------------------------------------------------ grants

    @Schema(name = "MembershipGrantRequest")
    record GrantRequest(
            @NotBlank @Size(max = 120) String customerId,
            @NotBlank @Size(max = 40) String tierCode,
            @NotNull @Min(1) @Max(365) Integer days,
            @NotBlank @Size(max = 500) String reason) {
    }

    @Schema(name = "MembershipGrant", description = "Free membership days granted by a program")
    record GrantDto(UUID membershipId, String customerId, String tierCode, int days, boolean replay,
                    Instant benefitsUntil, boolean newMembership) {

        static GrantDto of(Grant g) {
            return new GrantDto(g.membershipId(), g.customerId(), g.tierCode(), g.days(), g.replay(),
                    g.benefitsUntil(), g.newMembership());
        }
    }

    // ------------------------------------------------------------------ settings

    @Schema(name = "MembershipSettings", description = "Billing rules staff set")
    record SettingsDto(int refundWindowDays, List<Integer> retryDays, String updatedBy, Instant updatedAt,
                       long version) {

        static SettingsDto of(MembershipSettings s) {
            return new SettingsDto(s.refundWindowDays(), s.retryDays(), s.updatedBy(), s.updatedAt(), s.version());
        }
    }

    @Schema(name = "MembershipSettingsUpdate")
    record SettingsUpdate(
            @Min(0) @Max(60) int refundWindowDays,
            @NotNull @Size(max = 6) List<@Min(1) @Max(30) Integer> retryDays,
            @NotBlank @Size(max = 500) @Schema(description = "At least 15 characters") String reason,
            long expectedVersion) {
    }

    @Schema(name = "MembershipSettingsChange")
    record SettingsChangeDto(UUID id, String changedBy, Instant changedAt, String reason, String beforeJson,
                             String afterJson) {

        static SettingsChangeDto of(MembershipSettingsRepository.Change c) {
            return new SettingsChangeDto(c.id(), c.changedBy(), c.changedAt(), c.reason(), c.beforeJson(),
                    c.afterJson());
        }
    }

    @Schema(name = "MembershipRefundRequest")
    record RefundRequest(@NotBlank @Size(max = 500) @Schema(description = "At least 15 characters") String reason) {
    }

    // ------------------------------------------------------------------ report

    @Schema(name = "MembershipBenefitCost", description = "What one benefit cost in the period")
    record CostDto(BenefitCode benefit, long uses, long amountMinor) {

        static CostDto of(BenefitCostLine c) {
            return new CostDto(c.benefit(), c.uses(), c.amountMinor());
        }
    }

    @Schema(name = "MembershipTierFigures", description = "One tier's members, churn and revenue")
    record FiguresDto(String tierCode, String tierName, String status, long members, long trialing, long active,
                      long pastDue, long cancelling, long mrrMinor, long joined, long canceled, long lapsed,
                      long refunded, long membersAtStart, int churnBps, long chargedMinor, long refundedMinor) {

        static FiguresDto of(TierFigures f) {
            return new FiguresDto(f.tierCode(), f.tierName(), f.status(), f.members(), f.trialing(), f.active(),
                    f.pastDue(), f.cancelling(), f.mrrMinor(), f.joined(), f.canceled(), f.lapsed(), f.refunded(),
                    f.membersAtStart(), f.churnBps(), f.chargedMinor(), f.refundedMinor());
        }
    }

    @Schema(name = "MembershipReport", description = "Members per tier, churn, what the benefits cost, recurring "
            + "revenue and credit granted for [from, to)")
    record ReportDto(Instant from, Instant to, String currency, long liveMembers, long trialing, long mrrMinor,
                     long arrMinor, long chargedMinor, long refundedMinor, long netRevenueMinor,
                     long benefitCostMinor, long creditGrantedMinor, long feesWaivedMinor, long membersAtStart,
                     long joined, long ended, long lapsed, long canceled, long refunded, int churnBps,
                     List<FiguresDto> tiers, List<CostDto> costs) {

        static ReportDto of(Report r) {
            return new ReportDto(r.from(), r.to(), r.currency(), r.liveMembers(), r.trialing(), r.mrrMinor(),
                    r.arrMinor(), r.chargedMinor(), r.refundedMinor(), r.netRevenueMinor(), r.benefitCostMinor(),
                    r.creditGrantedMinor(), r.feesWaivedMinor(), r.membersAtStart(), r.joined(), r.ended(),
                    r.lapsed(), r.canceled(), r.refunded(), r.churnBps(),
                    r.tiers().stream().map(FiguresDto::of).toList(), r.costs().stream().map(CostDto::of).toList());
        }
    }
}
