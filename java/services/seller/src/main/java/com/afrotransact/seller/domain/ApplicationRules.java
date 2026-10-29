package com.afrotransact.seller.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Validation of the onboarding wizard. Step saves validate only the fields present ({@code complete=false});
 * submission validates everything ({@code complete=true}).
 */
public interface ApplicationRules {

    /** Throws {@link CountryNotLiveException} for a non-live market. Normalises the phone to E.164. */
    Validated<BusinessSection> business(BusinessSection in, RuleContext ctx, boolean complete);

    /** Entity-specific rules; {@code taxId} is checked against the entity's tax ID kind and formatted. */
    Validated<BusinessDetailsInput> businessDetails(BusinessDetailsInput in, EntityType entityType,
                                                    boolean hasStoredTaxId, RuleContext ctx, boolean complete);

    Validated<TaxIdCheck> taxId(EntityType entityType, String raw);

    Validated<List<Owner>> owners(List<Owner> owners, EntityType entityType, RuleContext ctx, boolean complete);

    Validated<PlanSection> plan(PlanSection in, Optional<Plan> plan, ApplicationData data, RuleContext ctx,
                                boolean complete);

    Validated<ComplianceSection> compliance(ComplianceSection in, Offering offering, RuleContext ctx,
                                            boolean complete);

    /** Every rule, with every required field: the submit check. */
    Map<String, String> submission(ApplicationData data, Optional<Plan> plan, RuleContext ctx);

    /** Steps whose required data is complete (for the wizard's progress). */
    List<OnboardingStep> completedSteps(ApplicationData data, Optional<Plan> plan, RuleContext ctx);
}
