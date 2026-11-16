package com.afrotransact.mcp.adapter.out.module;

import java.util.Map;

import com.afrotransact.catalog.api.CatalogUserApi;
import com.afrotransact.mcp.port.CallerCredentials;
import com.afrotransact.mcp.port.CatalogApi;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link CatalogApi} through catalog's published {@link CatalogUserApi} inside this JVM (mode {@code in-process}), run as the signed-in
 * person captured at request entry. The bearer token the port still carries is not read.
 */
@Component
@ConditionalOnModuleMode(caller = "mcp-server", callee = "catalog", value = ModuleMode.IN_PROCESS)
class InProcessCatalogApi implements CatalogApi {

    private static final String SERVICE = "catalog";

    private final ObjectProvider<CatalogUserApi> api;
    private final ModuleUpstream upstream;

    InProcessCatalogApi(ObjectProvider<CatalogUserApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(CatalogUserApi.class, true, false).length > 0, "mcp-server",
                "catalog", "at.mcpserver.catalog.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Downstream publicProduct(String atin, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().publicProduct(user, atin));
    }

    @Override
    public Downstream publicVariant(String atin, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().publicVariant(user, atin));
    }

    @Override
    public Downstream products(String orgId, String status, String query, int page, int size, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().products(user, orgId, status, query, page, size));
    }

    @Override
    public Downstream product(String idOrAtin, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().product(user, idOrAtin));
    }

    @Override
    public Downstream requestCategory(Map<String, Object> request, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().requestCategory(user, request));
    }

    @Override
    public Downstream categoryRequests(String status, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().categoryRequests(user, status));
    }
}
