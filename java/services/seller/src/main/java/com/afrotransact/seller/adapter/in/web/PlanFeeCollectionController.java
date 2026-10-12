package com.afrotransact.seller.adapter.in.web;

import com.afrotransact.seller.adapter.in.web.PlanFeeDtos.ChooseRequest;
import com.afrotransact.seller.adapter.in.web.PlanFeeDtos.CollectionResponse;
import com.afrotransact.seller.adapter.in.web.PlanFeeDtos.OverrideRequest;
import com.afrotransact.seller.adapter.in.web.PlanFeeDtos.SettingsRequest;
import com.afrotransact.seller.adapter.in.web.PlanFeeDtos.SettingsResponse;
import com.afrotransact.seller.application.PlanFeeCollectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Plan fee collection", description = "How each organisation pays its plan (S298): from its sales first "
        + "(EARNINGS_FIRST) or always by card (CARD). Changes apply from the next invoice.")
@RestController
class PlanFeeCollectionController {

    private final PlanFeeCollectionService planFees;

    PlanFeeCollectionController(PlanFeeCollectionService planFees) {
        this.planFees = planFees;
    }

    @Operation(summary = "How the organisation pays its plan, with the change log (owner / finance, billing staff)")
    @GetMapping("/v1/orgs/{orgId}/billing/plan-fee-collection")
    CollectionResponse planFeeCollection(@PathVariable String orgId) {
        return CollectionResponse.of(planFees.view(orgId));
    }

    @Operation(summary = "Pay the plan from sales (EARNINGS_FIRST) or always by card (CARD) (owner / finance)",
            description = "Applies from the next invoice. 409 seller.earnings-collection-off while the kill switch "
                    + "COLLECT_PLAN_FEES_FROM_EARNINGS is off.")
    @PutMapping("/v1/orgs/{orgId}/billing/plan-fee-collection")
    CollectionResponse choosePlanFeeCollection(@PathVariable String orgId, @RequestBody ChooseRequest body) {
        return CollectionResponse.of(planFees.choose(orgId, body.mode()));
    }

    @Operation(summary = "Staff override of how the organisation pays its plan (billing:adjust)",
            description = "Audited with a billing reason code (OTHER needs a note). Applies from the next invoice. "
                    + "409 seller.earnings-collection-off while the kill switch is off.")
    @PutMapping("/v1/orgs/{orgId}/billing/plan-fee-collection/override")
    CollectionResponse overridePlanFeeCollection(@PathVariable String orgId, @RequestBody OverrideRequest body) {
        return CollectionResponse.of(planFees.override(orgId, body.mode(), body.reasonCode(), body.note()));
    }

    @Operation(summary = "Platform billing settings: the kill switch and the default for new sellers (billing staff)")
    @GetMapping("/v1/billing/settings")
    SettingsResponse billingSettings() {
        return SettingsResponse.of(planFees.settings());
    }

    @Operation(summary = "Set how new sellers pay their plan unless they choose (plans:manage)")
    @PutMapping("/v1/billing/settings")
    SettingsResponse updateBillingSettings(@RequestBody SettingsRequest body) {
        return SettingsResponse.of(planFees.setDefault(body.planFeeCollectionDefault()));
    }
}
