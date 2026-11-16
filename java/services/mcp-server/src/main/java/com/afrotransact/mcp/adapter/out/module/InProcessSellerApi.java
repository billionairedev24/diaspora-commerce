package com.afrotransact.mcp.adapter.out.module;

import com.afrotransact.mcp.port.CallerCredentials;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.SellerApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.seller.api.SellerUserApi;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link SellerApi} (account health) through seller's published {@link SellerUserApi} inside this JVM (mode {@code in-process}), run as
 * the signed-in person captured at request entry. The bearer token the port still carries is not read.
 */
@Component
@ConditionalOnModuleMode(caller = "mcp-server", callee = "seller", value = ModuleMode.IN_PROCESS)
class InProcessSellerApi implements SellerApi {

    private static final String SERVICE = "seller";

    private final ObjectProvider<SellerUserApi> api;
    private final ModuleUpstream upstream;

    InProcessSellerApi(ObjectProvider<SellerUserApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(SellerUserApi.class, true, false).length > 0, "mcp-server",
                "seller", "at.mcpserver.seller.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Downstream accountHealth(String orgId, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().accountHealth(user, orgId));
    }
}
