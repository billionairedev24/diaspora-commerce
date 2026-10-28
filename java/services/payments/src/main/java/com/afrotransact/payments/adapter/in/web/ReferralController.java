package com.afrotransact.payments.adapter.in.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.application.ReferralService;
import com.afrotransact.payments.application.ReferralService.Entry;
import com.afrotransact.payments.domain.Referral;
import com.afrotransact.payments.domain.ReferralReward;
import com.afrotransact.payments.domain.ReferralRewardGrant;
import com.afrotransact.payments.domain.ReferralSettings;
import com.afrotransact.payments.domain.ReferralStatus;
import com.afrotransact.payments.port.ReferralRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Referrals", description = "The referral program (S322): every customer has a code; when a friend who "
        + "signed up with it has a first order delivered (and its return window passed) both are rewarded by "
        + "AfroTransact, as credit or membership days. Flagged referrals wait for staff.")
@RestController
class ReferralController {

    private final ReferralService referrals;

    ReferralController(ReferralService referrals) {
        this.referrals = referrals;
    }

    // ------------------------------------------------------------------ shapes

    @Schema(name = "ReferralRewardTerms", description = "What one side earns")
    record RewardTerms(@Schema(allowableValues = {"CREDIT", "MEMBERSHIP_DAYS"}) String type, long creditMinor,
                       int membershipDays) {
    }

    @Schema(name = "ReferralProgramTerms", description = "The offer in force")
    record ProgramTerms(boolean enabled, RewardTerms referrerReward, RewardTerms referredReward, long minOrderMinor,
                        int returnWindowDays, int validityDays, String currency) {
    }

    @Schema(name = "ReferralEarnings")
    record EarningsView(int invited, int open, int rewarded, long earnedMinor, int membershipDays, long pendingMinor) {
    }

    @Schema(name = "MyReferral", description = "A friend the customer invited, or the customer's own referral")
    record MyReferralView(UUID id, @Schema(description = "First name and last initial") String friendName,
                          @Schema(allowableValues = {"PENDING", "WAITING", "REWARDED", "EXPIRED", "CLOSED"})
                          String status, Instant signedUpAt, Instant expiresAt, Instant eligibleAt,
                          @Schema(description = "What the customer earns from this referral; null when nothing")
                          MyReward reward) {
    }

    @Schema(name = "MyReferralReward")
    record MyReward(RewardTerms terms, boolean granted, Instant grantedAt) {
    }

    @Schema(name = "ReferralOverview")
    record OverviewView(String code, String displayCode, ProgramTerms program, EarningsView earnings,
                        @Schema(description = "The customer's own referral when someone referred them; else null")
                        MyReferralView asFriend) {
    }

    @Schema(name = "ReferralRewardLine", description = "One side's reward of a referral (staff)")
    record RewardLine(@Schema(allowableValues = {"REFERRER", "REFERRED"}) String side, String customerId,
                      RewardTerms terms, String currency,
                      @Schema(allowableValues = {"PENDING", "GRANTED", "DEFERRED", "CAPPED", "VOID"}) String status,
                      Instant grantedAt) {
    }

    @Schema(name = "Referral", description = "A referral as staff see it")
    record ReferralView(UUID id, String referrerId, String referredId, String referredName,
                        @Schema(allowableValues = {"PENDING", "WAITING", "IN_REVIEW", "PARTIALLY_REWARDED",
                                "REWARDED", "REJECTED", "EXPIRED"}) String status,
                        @Schema(description = "Abuse checks that fired (SAME_DEVICE, SAME_NETWORK, SAME_ADDRESS, "
                                + "SAME_PAYMENT_METHOD)") List<String> flags,
                        Instant signedUpAt, Instant expiresAt, String qualifyingOrderId, Long qualifyingMinor,
                        Instant deliveredAt, Instant eligibleAt,
                        @Schema(allowableValues = {"APPROVED", "REJECTED", "SYSTEM"}) String decision,
                        String decisionReason, String decidedBy, Instant decidedAt, List<RewardLine> rewards,
                        @Schema(description = "Credit AfroTransact has paid for this referral, both sides")
                        long costMinor, Instant createdAt) {
    }

    @Schema(name = "ReferralSummary", description = "What the program has cost (staff)")
    record SummaryView(long costMinor, int membershipDays, long pendingMinor, String currency,
                       Map<String, Integer> byStatus, boolean enabled) {
    }

    @Schema(name = "ReferralDecisionRequest")
    record DecisionRequest(@NotBlank @Size(max = 500) @Schema(description = "At least 15 characters") String reason) {
    }

    // ------------------------------------------------------------------ customer

    @Operation(operationId = "getMyReferral", summary = "The signed-in customer's referral code, the offer and their "
            + "earnings (customer)", description = "The code is created on first use and never changes.")
    @GetMapping("/v1/referrals/me")
    OverviewView mine() {
        ReferralService.Overview o = referrals.myReferral();
        return new OverviewView(o.code(), o.displayCode(), terms(o.program(), o.currency()), earnings(o.earnings()),
                o.asFriend().map(e -> mine(e, false)).orElse(null));
    }

    @Operation(operationId = "listMyReferrals", summary = "Friends the signed-in customer referred, newest first, "
            + "with what they earned from each (customer)")
    @GetMapping("/v1/referrals/me/friends")
    List<MyReferralView> myFriends(@RequestParam(defaultValue = "100") int limit) {
        return referrals.myReferrals(limit).stream().map(e -> mine(e, true)).toList();
    }

    // ------------------------------------------------------------------ staff

    @Operation(operationId = "listReferrals", summary = "Referrals, newest first (payments:read)")
    @GetMapping("/v1/referrals")
    List<ReferralView> list(@RequestParam(required = false) ReferralStatus status,
                            @RequestParam(required = false) String customerId,
                            @RequestParam(defaultValue = "100") int limit) {
        return referrals.list(status, customerId, limit).stream().map(ReferralController::staff).toList();
    }

    @Operation(operationId = "referralSummary", summary = "What the program has cost and referrals per status "
            + "(payments:read)")
    @GetMapping("/v1/referrals/summary")
    SummaryView summary() {
        ReferralService.Summary s = referrals.summary();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        s.totals().byStatus().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> byStatus.put(e.getKey().name(), e.getValue()));
        return new SummaryView(s.totals().costMinor(), s.totals().membershipDays(), s.totals().pendingMinor(),
                s.currency(), byStatus, s.settings().enabled());
    }

    @Operation(operationId = "getReferral", summary = "One referral with its rewards (payments:read)")
    @GetMapping("/v1/referrals/{id}")
    ReferralView get(@PathVariable UUID id) {
        return staff(referrals.get(id));
    }

    @Operation(operationId = "approveReferral", summary = "Clear a flagged referral (ledger:adjust)",
            description = "It then waits for the return window like any other, and its rewards are granted at once "
                    + "if that has passed. Needs a reason of at least 15 characters.")
    @PostMapping("/v1/referrals/{id}/approve")
    ReferralView approve(@PathVariable UUID id, @Valid @RequestBody DecisionRequest body) {
        return staff(referrals.approve(id, body.reason()));
    }

    @Operation(operationId = "rejectReferral", summary = "Reject a flagged or waiting referral (ledger:adjust)",
            description = "No reward is granted. Needs a reason of at least 15 characters.")
    @PostMapping("/v1/referrals/{id}/reject")
    ReferralView reject(@PathVariable UUID id, @Valid @RequestBody DecisionRequest body) {
        return staff(referrals.reject(id, body.reason()));
    }

    // ------------------------------------------------------------------ mapping

    private static RewardTerms terms(ReferralReward r) {
        return new RewardTerms(r.type().name(), r.creditMinor(), r.membershipDays());
    }

    private static ProgramTerms terms(ReferralSettings s, String currency) {
        return new ProgramTerms(s.enabled(), terms(s.referrer()), terms(s.referred()), s.minOrderMinor(),
                s.returnWindowDays(), s.validityDays(), currency);
    }

    private static EarningsView earnings(ReferralRepository.Earnings e) {
        return new EarningsView(e.invited(), e.open(), e.rewarded(), e.earnedMinor(), e.membershipDays(),
                e.pendingMinor());
    }

    /** What a customer sees: flags and staff decisions stay hidden, a held or part-rewarded referral just waits. */
    private static MyReferralView mine(Entry e, boolean asReferrer) {
        Referral r = e.referral();
        String status = switch (r.status()) {
            case PENDING -> "PENDING";
            case WAITING, IN_REVIEW, PARTIALLY_REWARDED -> "WAITING";
            case REWARDED -> "REWARDED";
            case EXPIRED -> "EXPIRED";
            case REJECTED -> "CLOSED";
        };
        String side = asReferrer ? "REFERRER" : "REFERRED";
        MyReward reward = e.rewards().stream().filter(g -> g.side().name().equals(side)
                && g.status() != ReferralRewardGrant.Status.VOID && g.status() != ReferralRewardGrant.Status.CAPPED)
                .findFirst().map(g -> new MyReward(terms(g.reward()), g.status() == ReferralRewardGrant.Status.GRANTED,
                        g.grantedAt())).orElse(null);
        return new MyReferralView(r.id(), asReferrer ? r.referredName() : null, status, r.signedUpAt(),
                r.expiresAt(), r.eligibleAt(), reward);
    }

    private static ReferralView staff(Entry e) {
        Referral r = e.referral();
        List<RewardLine> lines = e.rewards().stream().map(g -> new RewardLine(g.side().name(), g.customerId(),
                terms(g.reward()), g.currency(), g.status().name(), g.grantedAt())).toList();
        long cost = e.rewards().stream().filter(g -> g.status() == ReferralRewardGrant.Status.GRANTED)
                .mapToLong(g -> g.reward().creditMinor()).sum();
        return new ReferralView(r.id(), r.referrerId(), r.referredId(), r.referredName(), r.status().name(),
                r.flags().stream().map(Enum::name).toList(), r.signedUpAt(), r.expiresAt(), r.qualifyingOrderId(),
                r.qualifyingMinor(), r.deliveredAt(), r.eligibleAt(),
                r.decision() == null ? null : r.decision().name(), r.decisionReason(), r.decidedBy(), r.decidedAt(),
                lines, cost, r.createdAt());
    }
}
