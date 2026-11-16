package com.afrotransact.mcp.adapter.out.module;

import com.afrotransact.mcp.port.ApplicationsApi;
import com.afrotransact.mcp.port.CallerCredentials;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.seller.api.SellerUserApi;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link ApplicationsApi} (review queue and masked dossier) through seller's published {@link SellerUserApi} inside this JVM (mode
 * {@code in-process}), run as the signed-in reviewer captured at request entry: seller's own reviewer permissions decide, and a reviewer
 * who holds the PII permission is logged as over HTTP. The bearer token the port still carries is not read.
 */
@Component
@ConditionalOnModuleMode(caller = "mcp-server", callee = "seller", value = ModuleMode.IN_PROCESS)
class InProcessApplicationsApi implements ApplicationsApi {

    private static final String SERVICE = "seller";

    private final ObjectProvider<SellerUserApi> api;
    private final ModuleUpstream upstream;

    InProcessApplicationsApi(ObjectProvider<SellerUserApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(SellerUserApi.class, true, false).length > 0, "mcp-server",
                "seller", "at.mcpserver.seller.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Downstream queue(String status, int page, int size, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().applicationQueue(user, status, page, size));
    }

    @Override
    public Downstream application(String applicationId, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().application(user, applicationId));
    }
}
