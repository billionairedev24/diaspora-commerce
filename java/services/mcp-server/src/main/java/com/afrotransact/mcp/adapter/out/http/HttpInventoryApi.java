package com.afrotransact.mcp.adapter.out.http;

import java.time.Duration;
import java.util.List;

import com.afrotransact.mcp.port.CallerCredentials;
import com.afrotransact.mcp.port.InventoryApi;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** inventory-service (port 8103): availability and positions. */
@Component
@ConditionalOnModuleMode(caller = "mcp-server", callee = "inventory", value = ModuleMode.HTTP)
class HttpInventoryApi implements InventoryApi {

    private final JsonHttp http;

    HttpInventoryApi(RestClient.Builder builder, @Value("${at.mcp.downstream.inventory-url}") String baseUrl,
            @Value("${at.mcp.downstream.timeout:5s}") Duration timeout) {
        this.http = new JsonHttp(builder, "inventory", baseUrl, timeout);
    }

    @Override
    public Downstream availability(List<String> skus, CallerCredentials credentials) {
        return http.call(credentials.bearerToken(), c -> c.get().uri(u -> u.path("/v1/availability")
                .queryParam("skus", String.join(",", skus)).build()));
    }

    @Override
    public Downstream positions(String ownerOrg, String sku, boolean lowStock, CallerCredentials credentials) {
        return http.call(credentials.bearerToken(), c -> c.get().uri(u -> {
            u.path("/v1/positions").queryParam("lowStock", lowStock).queryParam("size", 50);
            if (ownerOrg != null) {
                u.queryParam("ownerOrg", ownerOrg);
            }
            if (sku != null) {
                u.queryParam("sku", sku);
            }
            return u.build();
        }));
    }
}
