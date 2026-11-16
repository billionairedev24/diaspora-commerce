package com.afrotransact.mcp.adapter.out.module;

import com.afrotransact.mcp.port.CallerCredentials;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.PaymentsApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.payments.api.PayoutReadApi;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link PaymentsApi} through payments' published {@link PayoutReadApi} inside this JVM (mode {@code in-process}), run as the signed-in
 * person captured at request entry. The bearer token the port still carries is not read.
 */
@Component
@ConditionalOnModuleMode(caller = "mcp-server", callee = "payments", value = ModuleMode.IN_PROCESS)
class InProcessPaymentsApi implements PaymentsApi {

    private static final String SERVICE = "payments";

    private final ObjectProvider<PayoutReadApi> api;
    private final ModuleUpstream upstream;

    InProcessPaymentsApi(ObjectProvider<PayoutReadApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(PayoutReadApi.class, true, false).length > 0, "mcp-server",
                "payments", "at.mcpserver.payments.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Downstream payoutBalance(String orgId, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().balance(user, orgId));
    }

    @Override
    public Downstream payoutRequests(String orgId, int limit, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().requests(user, orgId, limit));
    }
}
