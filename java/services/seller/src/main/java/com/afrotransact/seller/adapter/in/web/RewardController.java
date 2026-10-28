package com.afrotransact.seller.adapter.in.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.seller.adapter.in.web.PlanAdminDtos.StatusBody;
import com.afrotransact.seller.adapter.in.web.RewardDtos.GrantPage;
import com.afrotransact.seller.adapter.in.web.RewardDtos.GrantView;
import com.afrotransact.seller.adapter.in.web.RewardDtos.PreviewResponse;
import com.afrotransact.seller.adapter.in.web.RewardDtos.ProgressView;
import com.afrotransact.seller.adapter.in.web.RewardDtos.ReasonView;
import com.afrotransact.seller.adapter.in.web.RewardDtos.RuleBody;
import com.afrotransact.seller.adapter.in.web.RewardDtos.RuleResponse;
import com.afrotransact.seller.adapter.in.web.RewardDtos.SellerRewardsResponse;
import com.afrotransact.seller.application.BillingAdjustmentService;
import com.afrotransact.seller.application.RewardService;
import com.afrotransact.seller.domain.GrantSource;
import com.afrotransact.seller.domain.GrantType;
import com.afrotransact.seller.domain.RewardRuleStatus;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.port.BillingGrantRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Rewards", description = "Reward rules (staff with plans:manage), the grants log, and the rewards an "
        + "organisation can earn (owner / finance)")
@RestController
class RewardController {

    private final RewardService rewards;
    private final BillingAdjustmentService adjustments;

    RewardController(RewardService rewards, BillingAdjustmentService adjustments) {
        this.rewards = rewards;
        this.adjustments = adjustments;
    }

    @Operation(summary = "Reward rules (staff)")
    @GetMapping("/v1/billing/reward-rules")
    List<RuleResponse> listRewardRules() {
        return rewards.rules().stream().map(RuleResponse::of).toList();
    }

    @Operation(summary = "Create a reward rule (DRAFT unless a status is given)")
    @PostMapping("/v1/billing/reward-rules")
    @ResponseStatus(HttpStatus.CREATED)
    RuleResponse createRewardRule(@RequestBody RuleBody body) {
        return RuleResponse.of(rewards.create(body.toInput()));
    }

    @Operation(summary = "One reward rule (staff)")
    @GetMapping("/v1/billing/reward-rules/{id}")
    RuleResponse getRewardRule(@PathVariable UUID id) {
        return RuleResponse.of(rewards.rule(id));
    }

    @Operation(summary = "Edit a reward rule (expectedVersion required)")
    @PutMapping("/v1/billing/reward-rules/{id}")
    RuleResponse updateRewardRule(@PathVariable UUID id, @RequestBody RuleBody body) {
        if (body.expectedVersion() == null) {
            throw new SellerRuleViolation("seller.invalid-reward-rule", "expectedVersion is required",
                    Map.of("expectedVersion", "Required"));
        }
        return RuleResponse.of(rewards.update(id, body.toInput(), body.expectedVersion()));
    }

    @Operation(summary = "Activate, pause or end a reward rule")
    @PostMapping("/v1/billing/reward-rules/{id}/status")
    RuleResponse setRewardRuleStatus(@PathVariable UUID id, @RequestBody StatusBody body) {
        RewardRuleStatus s = Inputs.enumOf(RewardRuleStatus.class, body.status(), "status");
        if (s == null) {
            throw new SellerRuleViolation("seller.invalid-input", "status is required", Map.of("status", "Required"));
        }
        return RuleResponse.of(rewards.setStatus(id, s));
    }

    @Operation(summary = "Who qualifies now under a rule (unsaved or edited; nothing is granted)")
    @PostMapping("/v1/billing/reward-rules/preview")
    PreviewResponse previewRewardRule(@RequestBody RuleBody body) {
        return PreviewResponse.of(rewards.preview(body.toInput(), body.ruleId() == null || body.ruleId().isBlank()
                ? null : UUID.fromString(body.ruleId())));
    }

    @Operation(summary = "Grants log: rewards and staff adjustments, newest first (staff)")
    @GetMapping("/v1/billing/grants")
    GrantPage listGrants(@RequestParam(required = false) String orgId,
                         @RequestParam(required = false) String source, @RequestParam(required = false) String type,
                         @RequestParam(required = false) UUID ruleId, @RequestParam(required = false) String q,
                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return GrantPage.of(adjustments.grants(new BillingGrantRepository.GrantFilter(blank(orgId),
                Inputs.enumOf(GrantSource.class, source, "source"), Inputs.enumOf(GrantType.class, type, "type"),
                ruleId, blank(q)), page, size));
    }

    @Operation(summary = "Reason codes for staff adjustments")
    @GetMapping("/v1/billing/reason-codes")
    List<ReasonView> listBillingReasonCodes() {
        return adjustments.reasonCodes().stream().map(ReasonView::of).toList();
    }

    @Operation(summary = "Rewards the organisation can earn (with progress) and what it received (owner / finance)")
    @GetMapping("/v1/orgs/{orgId}/billing/rewards")
    SellerRewardsResponse sellerRewards(@PathVariable String orgId) {
        return new SellerRewardsResponse(rewards.progress(orgId).stream().map(ProgressView::of).toList(),
                rewards.earned(orgId).stream().map(GrantView::ofEarned).toList());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
