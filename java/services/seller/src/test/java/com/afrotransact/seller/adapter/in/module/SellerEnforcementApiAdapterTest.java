package com.afrotransact.seller.adapter.in.module;

import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.seller.api.SellerEnforcementApi;
import com.afrotransact.seller.application.SellerEnforcementService;
import com.afrotransact.seller.domain.SuspensionReason;
import org.junit.jupiter.api.Test;

/** S6 for the enforcement calls: {@code sellers:enforce} is required, a caller without it is refused with 403. */
class SellerEnforcementApiAdapterTest {

    private final SellerEnforcementApi api = new SellerEnforcementApiAdapter(
            InProcessKit.guarded(SellerEnforcementService.class, "sellers:enforce"), InProcessKit.ACTORS);

    @Test
    void suspendWithoutTheScopeIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("trust-ops"),
                () -> api.suspend("org", SuspensionReason.values()[0].name(), "note"));
    }

    @Test
    void reinstateWithoutTheScopeIs403() {
        InProcessKit.assertForbidden(InProcessKit.unscoped("trust-ops"), () -> api.reinstate("org", "note"));
    }
}
