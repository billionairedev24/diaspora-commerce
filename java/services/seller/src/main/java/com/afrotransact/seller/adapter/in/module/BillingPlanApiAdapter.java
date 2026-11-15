package com.afrotransact.seller.adapter.in.module;

import java.util.Optional;

import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.api.BillingPlanApi;
import com.afrotransact.seller.application.BillingOverview;
import com.afrotransact.seller.application.BillingService;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link BillingPlanApi}: {@code GET /v1/orgs/{orgId}/billing} under the calling module's principal;
 * {@link BillingService} checks {@code seller:billing} as for a token.
 */
@Component
class BillingPlanApiAdapter implements BillingPlanApi {

    private final BillingService billing;
    private final ModuleEntry entry;

    BillingPlanApiAdapter(BillingService billing, ActorResolver actors) {
        this.billing = billing;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public Optional<Plan> currentPlan(String orgId) {
        return entry.run(() -> {
            try {
                BillingOverview overview = billing.overview(orgId);
                String code = overview.subscription().planCode();
                return Optional.of(new Plan(code == null || code.isBlank() ? null : code,
                        overview.subscription().status().name()));
            } catch (NotFoundException notFound) {
                return Optional.<Plan>empty();
            }
        });
    }
}
