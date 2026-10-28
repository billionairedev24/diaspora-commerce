package com.afrotransact.seller.adapter.in.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.seller.adapter.in.web.PlanAdminDtos.CampaignBody;
import com.afrotransact.seller.adapter.in.web.PlanAdminDtos.CampaignResponse;
import com.afrotransact.seller.adapter.in.web.PlanAdminDtos.CreatePlanBody;
import com.afrotransact.seller.adapter.in.web.PlanAdminDtos.InvitesBody;
import com.afrotransact.seller.adapter.in.web.PlanAdminDtos.MigrationBody;
import com.afrotransact.seller.adapter.in.web.PlanAdminDtos.MigrationPreviewResponse;
import com.afrotransact.seller.adapter.in.web.PlanAdminDtos.MigrationResultResponse;
import com.afrotransact.seller.adapter.in.web.PlanAdminDtos.PlanAdminResponse;
import com.afrotransact.seller.adapter.in.web.PlanAdminDtos.PricesBody;
import com.afrotransact.seller.adapter.in.web.PlanAdminDtos.StatusBody;
import com.afrotransact.seller.adapter.in.web.PlanAdminDtos.UpdatePlanBody;
import com.afrotransact.seller.application.PlanAdminService;
import com.afrotransact.seller.application.TrialService;
import com.afrotransact.seller.domain.CampaignStatus;
import com.afrotransact.seller.domain.PlanPricesInput;
import com.afrotransact.seller.domain.SellerRuleViolation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Plan catalogue", description = "AfroTransact owns plans, versions (prices per cadence and the default "
        + "trial) and trial campaigns; AfroTransact bills and collects itself. Staff with plans:manage change them; "
        + "plans:manage, seller:billing or billing:adjust read them.")
@RestController
class PlanAdminController {

    private final PlanAdminService plans;
    private final TrialService trials;

    PlanAdminController(PlanAdminService plans, TrialService trials) {
        this.plans = plans;
        this.trials = trials;
    }

    @Operation(summary = "Every plan with its versions, prices and subscribers (staff)")
    @GetMapping("/v1/billing/plans")
    List<PlanAdminResponse> listAdminPlans() {
        return plans.plans().stream().map(PlanAdminResponse::of).toList();
    }

    @Operation(summary = "Create a plan (draft, with version 1 as a draft)")
    @PostMapping("/v1/billing/plans")
    @ResponseStatus(HttpStatus.CREATED)
    PlanAdminResponse createPlan(@RequestBody CreatePlanBody body) {
        PricesBody p = body.prices() == null ? new PricesBody(null, null, null, null, null) : body.prices();
        return PlanAdminResponse.of(plans.create(body.toInput(), p.toInput()));
    }

    @Operation(summary = "One plan (staff)")
    @GetMapping("/v1/billing/plans/{code}")
    PlanAdminResponse getAdminPlan(@PathVariable String code) {
        return PlanAdminResponse.of(plans.plan(code));
    }

    @Operation(summary = "Edit a plan's name, tagline, features, limits, visibility and sort",
            description = "No new version. 409 seller.concurrent-update when expectedVersion is stale.")
    @PutMapping("/v1/billing/plans/{code}")
    PlanAdminResponse updatePlan(@PathVariable String code, @RequestBody UpdatePlanBody body) {
        return PlanAdminResponse.of(plans.update(code, body.toInput(), body.expectedVersion()));
    }

    @Operation(summary = "Save the draft version's prices and trial",
            description = "Creates the next version when prices or trial differ from the current one; subscribers "
                    + "stay on their version until migrated.")
    @PutMapping("/v1/billing/plans/{code}/draft")
    PlanAdminResponse savePlanDraft(@PathVariable String code, @RequestBody PricesBody body) {
        PlanPricesInput in = body.toInput();
        return PlanAdminResponse.of(plans.saveDraft(code, in));
    }

    @Operation(summary = "Publish the draft version (subscribers stay on theirs until migrated)")
    @PostMapping("/v1/billing/plans/{code}/publish")
    PlanAdminResponse publishPlan(@PathVariable String code) {
        return PlanAdminResponse.of(plans.publish(code));
    }

    @Operation(summary = "Retire a plan: no new subscribers; existing ones keep it")
    @PostMapping("/v1/billing/plans/{code}/retire")
    PlanAdminResponse retirePlan(@PathVariable String code) {
        return PlanAdminResponse.of(plans.retire(code));
    }

    @Operation(summary = "Organisations invited to an invite-only plan")
    @PutMapping("/v1/billing/plans/{code}/invites")
    PlanAdminResponse setPlanInvites(@PathVariable String code, @RequestBody InvitesBody body) {
        return PlanAdminResponse.of(plans.setInvites(code, body.orgIds()));
    }

    @Operation(summary = "Subscribers on older versions and what the current version will charge them")
    @GetMapping("/v1/billing/plans/{code}/migration")
    MigrationPreviewResponse previewPlanMigration(@PathVariable String code) {
        return MigrationPreviewResponse.of(plans.migrationPreview(code));
    }

    @Operation(summary = "Migrate subscribers to the current version (from their next renewal, no proration)")
    @PostMapping("/v1/billing/plans/{code}/migration")
    MigrationResultResponse migratePlan(@PathVariable String code, @RequestBody(required = false) MigrationBody body) {
        return MigrationResultResponse.of(plans.migrate(code, body == null ? null : body.orgIds()));
    }

    @Operation(summary = "Trial campaigns (promo codes and sign-up windows)")
    @GetMapping("/v1/billing/trial-campaigns")
    List<CampaignResponse> listTrialCampaigns() {
        return trials.campaigns().stream().map(CampaignResponse::of).toList();
    }

    @Operation(summary = "Create a trial campaign")
    @PostMapping("/v1/billing/trial-campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    CampaignResponse createTrialCampaign(@RequestBody CampaignBody body) {
        return CampaignResponse.of(trials.create(body.toInput()));
    }

    @Operation(summary = "Edit a trial campaign (expectedVersion required)")
    @PutMapping("/v1/billing/trial-campaigns/{id}")
    CampaignResponse updateTrialCampaign(@PathVariable UUID id, @RequestBody CampaignBody body) {
        if (body.expectedVersion() == null) {
            throw new SellerRuleViolation("seller.invalid-campaign", "expectedVersion is required",
                    Map.of("expectedVersion", "Required"));
        }
        return CampaignResponse.of(trials.update(id, body.toInput(), body.expectedVersion()));
    }

    @Operation(summary = "Activate, pause or end a trial campaign")
    @PostMapping("/v1/billing/trial-campaigns/{id}/status")
    CampaignResponse setTrialCampaignStatus(@PathVariable UUID id, @RequestBody StatusBody body) {
        CampaignStatus s = Inputs.enumOf(CampaignStatus.class, body.status(), "status");
        if (s == null) {
            throw new SellerRuleViolation("seller.invalid-input", "status is required",
                    Map.of("status", "Required"));
        }
        return CampaignResponse.of(trials.setStatus(id, s));
    }
}
