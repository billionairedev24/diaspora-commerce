package com.afrotransact.seller.adapter.in.module;

import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.seller.api.BillingPlanApi;
import com.afrotransact.seller.application.BillingService;
import org.junit.jupiter.api.Test;

/** S6 for the plan lookup: {@code seller:billing} is required, a caller without it is refused with 403. */
class BillingPlanApiAdapterTest {

    private final BillingPlanApi api = new BillingPlanApiAdapter(InProcessKit.guarded(BillingService.class, "seller:billing"),
            InProcessKit.ACTORS);

    @Test
    void currentPlanWithoutTheScopeIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("payments"), () -> api.currentPlan("org"));
    }
}
