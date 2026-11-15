package com.afrotransact.trustops.adapter.out.module;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.seller.api.SellerEnforcementApi;
import com.afrotransact.trustops.domain.SuspensionReason;
import com.afrotransact.trustops.port.SellerEnforcementClient;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link SellerEnforcementClient} through seller's published {@link SellerEnforcementApi} inside this JVM (mode
 * {@code in-process}), entered as trust-ops' caller principal (scope {@code sellers:enforce}). Notes are cut at 1000 characters
 * as in the HTTP client; both calls are idempotent.
 */
@Component
@ConditionalOnModuleMode(caller = "trust-ops", callee = "seller", value = ModuleMode.IN_PROCESS)
class InProcessSellerEnforcementClient implements SellerEnforcementClient {

    private static final String SERVICE = "seller";

    private final ObjectProvider<SellerEnforcementApi> api;
    private final ModuleUpstream upstream;

    InProcessSellerEnforcementClient(ObjectProvider<SellerEnforcementApi> api, ModuleUpstream upstream,
                                     ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(SellerEnforcementApi.class, true, false).length > 0,
                "trust-ops", "seller", "at.trustops.seller.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public void suspend(String orgId, SuspensionReason reason, String note) {
        upstream.run(SERVICE, () -> api.getObject().suspend(orgId, reason.name(), trim(note)));
    }

    @Override
    public void reinstate(String orgId, String note) {
        upstream.run(SERVICE, () -> api.getObject().reinstate(orgId, trim(note)));
    }

    private static String trim(String note) {
        return note == null ? null : note.length() > 1000 ? note.substring(0, 1000) : note;
    }
}
