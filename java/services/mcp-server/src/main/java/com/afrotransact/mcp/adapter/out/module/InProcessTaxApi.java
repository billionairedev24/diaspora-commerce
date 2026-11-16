package com.afrotransact.mcp.adapter.out.module;

import com.afrotransact.mcp.port.CallerCredentials;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.TaxApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.taxreturns.api.TaxReviewReadApi;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link TaxApi} through tax-returns' published {@link TaxReviewReadApi} inside this JVM (mode {@code in-process}), run as the signed-in
 * person captured at request entry: tax-returns' own {@code tax:read} check decides. The bearer token the port still carries is not read.
 */
@Component
@ConditionalOnModuleMode(caller = "mcp-server", callee = "tax-returns", value = ModuleMode.IN_PROCESS)
class InProcessTaxApi implements TaxApi {

    private static final String SERVICE = "tax-returns";

    private final ObjectProvider<TaxReviewReadApi> api;
    private final ModuleUpstream upstream;

    InProcessTaxApi(ObjectProvider<TaxReviewReadApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(TaxReviewReadApi.class, true, false).length > 0, "mcp-server",
                "tax-returns", "at.mcpserver.taxreturns.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Downstream reviewQueue(String status, String query, int page, int size, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().reviewQueue(user, status, query, page, size));
    }

    @Override
    public Downstream shadowReport(String from, String to, String provider, int size, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().shadowReport(user, from, to, provider, size));
    }
}
