package com.afrotransact.mcp.adapter.out.module;

import java.util.List;

import com.afrotransact.inventory.api.InventoryUserApi;
import com.afrotransact.mcp.port.CallerCredentials;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.InventoryApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link InventoryApi} through inventory's published {@link InventoryUserApi} inside this JVM (mode {@code in-process}), run as the
 * signed-in person captured at request entry. The bearer token the port still carries is not read.
 */
@Component
@ConditionalOnModuleMode(caller = "mcp-server", callee = "inventory", value = ModuleMode.IN_PROCESS)
class InProcessInventoryApi implements InventoryApi {

    private static final String SERVICE = "inventory";

    private final ObjectProvider<InventoryUserApi> api;
    private final ModuleUpstream upstream;

    InProcessInventoryApi(ObjectProvider<InventoryUserApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(InventoryUserApi.class, true, false).length > 0, "mcp-server",
                "inventory", "at.mcpserver.inventory.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Downstream availability(List<String> skus, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().availability(user, skus));
    }

    @Override
    public Downstream positions(String ownerOrg, String sku, boolean lowStock, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().positions(user, ownerOrg, sku, lowStock));
    }
}
