package com.afrotransact.seller.adapter.in.web;

import java.util.List;
import java.util.Map;

import com.afrotransact.seller.adapter.in.web.OnboardingDtos.ApplicationResponse;
import com.afrotransact.seller.adapter.in.web.OnboardingDtos.BusinessDetailsStep;
import com.afrotransact.seller.adapter.in.web.OnboardingDtos.BusinessStep;
import com.afrotransact.seller.adapter.in.web.OnboardingDtos.ComplianceStep;
import com.afrotransact.seller.adapter.in.web.OnboardingDtos.OwnerDto;
import com.afrotransact.seller.adapter.in.web.OnboardingDtos.OwnersStep;
import com.afrotransact.seller.adapter.in.web.OnboardingDtos.PlanStep;
import com.afrotransact.seller.application.OnboardingService;
import com.afrotransact.seller.application.StepInput;
import com.afrotransact.seller.domain.OnboardingStep;
import com.afrotransact.seller.domain.SellerRuleViolation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpEntity;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag(name = "Onboarding")
@RestController
class OnboardingStepController {

    private final OnboardingService onboarding;
    private final JsonMapper json;

    OnboardingStepController(OnboardingService onboarding, JsonMapper json) {
        this.onboarding = onboarding;
        this.json = json;
    }

    @Operation(summary = "Autosave one step",
            description = "Steps: business, business-details, owners, plan, catalog-compliance, review. The body "
                    + "is the step's data; only the fields present are validated (submit validates everything). "
                    + "stripe-verification and payouts are completed in Stripe's embedded components (Stripe "
                    + "collects the payout bank account); saving them answers 422 seller.step-managed-by-stripe.")
    @ApiResponse(responseCode = "422", description = "seller.invalid-application (fieldErrors), "
            + "seller.country-not-live, seller.step-not-applicable or seller.step-managed-by-stripe", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @PutMapping("/v1/applications/me/steps/{step}")
    ApplicationResponse saveStep(@PathVariable String step,
                                 @RequestBody(required = true, content = @Content(mediaType = "application/json",
                                         schema = @Schema(oneOf = {BusinessStep.class, BusinessDetailsStep.class,
                                             OwnersStep.class, PlanStep.class,
                                             ComplianceStep.class}))) HttpEntity<JsonNode> request) {
        OnboardingStep s = OnboardingStep.fromKey(step);
        JsonNode b = request.getBody() == null ? json.createObjectNode() : request.getBody();
        StepInput input = switch (s) {
            case BUSINESS -> StepInput.business(read(b, BusinessStep.class).toSection());
            case BUSINESS_DETAILS -> StepInput.businessDetails(read(b, BusinessDetailsStep.class).toInput());
            case OWNERS -> {
                OwnersStep owners = b.isArray() ? new OwnersStep(List.of(read(b, OwnerDto[].class)))
                        : read(b, OwnersStep.class);
                yield StepInput.owners(owners.owners() == null ? List.of()
                        : owners.owners().stream().map(OwnerDto::toOwner).toList());
            }
            case PLAN -> StepInput.plan(read(b, PlanStep.class).toSection());
            case CATALOG_COMPLIANCE -> StepInput.compliance(read(b, ComplianceStep.class).toSection());
            default -> StepInput.none();
        };
        return ApplicationResponse.of(onboarding.saveStep(s, input));
    }

    private <T> T read(JsonNode node, Class<T> type) {
        try {
            return json.treeToValue(node, type);
        } catch (JacksonException e) {
            throw new SellerRuleViolation("seller.invalid-input", "Malformed step data: " + e.getOriginalMessage(),
                    Map.of("body", "Malformed"));
        }
    }
}
