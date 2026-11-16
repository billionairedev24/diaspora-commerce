package com.afrotransact.mcp.adapter.out.http;

import java.time.Duration;

import com.afrotransact.mcp.port.CallerCredentials;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.SellerApi;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** seller-service (port 8106): account health of an organisation. */
@Component
@ConditionalOnModuleMode(caller = "mcp-server", callee = "seller", value = ModuleMode.HTTP)
class HttpSellerApi implements SellerApi {

    private final JsonHttp http;

    HttpSellerApi(RestClient.Builder builder, @Value("${at.mcp.downstream.seller-url}") String baseUrl,
            @Value("${at.mcp.downstream.timeout:5s}") Duration timeout) {
        this.http = new JsonHttp(builder, "seller", baseUrl, timeout);
    }

    @Override
    public Downstream accountHealth(String orgId, CallerCredentials credentials) {
        return http.call(credentials.bearerToken(), c -> c.get().uri("/v1/orgs/{orgId}/account-health", orgId));
    }
}
