package com.afrotransact.payments.adapter.out.module;

import java.util.Optional;

import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.payments.port.SellerPlanDirectory;
import com.afrotransact.seller.api.BillingPlanApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link SellerPlanDirectory} through seller's published {@link BillingPlanApi} inside this JVM (mode {@code in-process}),
 * entered as payments' caller principal (scope {@code seller:billing}). As over HTTP the answer is advisory: an organisation
 * with no billing record is a plan-less answer, and a refusal or failure is "unknown", so rates fall back to category rates.
 */
@Component
@ConditionalOnExpression("'${at.payments.seller.mode:${at.payments.mode:http}}' == 'in-process'")
class InProcessSellerPlanDirectory implements SellerPlanDirectory {

    private static final Logger LOG = LoggerFactory.getLogger(InProcessSellerPlanDirectory.class);

    private final ObjectProvider<BillingPlanApi> api;
    private final ObjectProvider<CallerPrincipals> callers;

    InProcessSellerPlanDirectory(ObjectProvider<BillingPlanApi> api, ObjectProvider<CallerPrincipals> callers,
                                 ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(BillingPlanApi.class, true, false).length > 0, "payments",
                "seller", "at.payments.seller.mode");
        this.api = api;
        this.callers = callers;
    }

    @Override
    public Optional<Plan> currentPlan(String orgId) {
        try {
            Optional<BillingPlanApi.Plan> plan = InProcessCalls.as(callers.getObject().principalFor("payments"),
                    () -> api.getObject().currentPlan(orgId));
            return Optional.of(plan.map(p -> new Plan(p.planCode(), p.status())).orElse(new Plan(null, null)));
        } catch (ModuleApiException e) {
            LOG.warn("Seller refused the plan of {} ({}): rates fall back to category rates", orgId, e.status());
            return Optional.empty();
        } catch (RuntimeException e) {
            LOG.warn("Seller unavailable for the plan of {} ({}): rates fall back to category rates", orgId,
                    e.getMessage());
            return Optional.empty();
        }
    }
}
