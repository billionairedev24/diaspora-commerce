package com.afrotransact.offers.adapter.in.module;

import com.afrotransact.offers.api.SellerStandingApi;
import com.afrotransact.offers.application.SellerStandingService;
import com.afrotransact.platform.test.InProcessKit;
import org.junit.jupiter.api.Test;

/** S6 for the seller standing calls: a caller without {@code offers:write} is refused with 403. */
class SellerStandingApiAdapterTest {

    private final SellerStandingApi api = new SellerStandingApiAdapter(
            InProcessKit.guarded(SellerStandingService.class, "offers:write"), InProcessKit.ACTORS);

    @Test
    void ratingWithoutTheScopeIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("trust-ops"), () -> api.rating("org"));
    }

    @Test
    void updatePerformanceWithoutTheScopeIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("trust-ops"),
                () -> api.updatePerformance("org", 4.0, 0.9, 0.01, false));
    }
}
