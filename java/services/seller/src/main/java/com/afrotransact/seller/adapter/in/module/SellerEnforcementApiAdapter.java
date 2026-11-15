package com.afrotransact.seller.adapter.in.module;

import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.seller.api.SellerEnforcementApi;
import com.afrotransact.seller.application.SellerEnforcementService;
import com.afrotransact.seller.domain.SuspensionReason;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link SellerEnforcementApi}: the use cases behind the suspend and reinstate endpoints, entered
 * under the calling module's principal; {@link SellerEnforcementService} checks {@code sellers:enforce} as for a token.
 */
@Component
class SellerEnforcementApiAdapter implements SellerEnforcementApi {

    private final SellerEnforcementService enforcement;
    private final ModuleEntry entry;

    SellerEnforcementApiAdapter(SellerEnforcementService enforcement, ActorResolver actors) {
        this.enforcement = enforcement;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public void suspend(String orgId, String reasonCode, String note) {
        entry.runVoid(() -> enforcement.suspend(orgId, SuspensionReason.valueOf(reasonCode), note));
    }

    @Override
    public void reinstate(String orgId, String note) {
        entry.runVoid(() -> enforcement.reinstate(orgId, note));
    }
}
