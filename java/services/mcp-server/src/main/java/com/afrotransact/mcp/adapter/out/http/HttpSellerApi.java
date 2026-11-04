package com.afrotransact.mcp.adapter.out.http;

import java.time.Duration;

import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.SellerApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** seller-service (port 8106): account health of an organisation. */
@Component
class HttpSellerApi implements SellerApi {

    private final JsonHttp http;

    HttpSellerApi(RestClient.Builder builder, @Value("${at.mcp.downstream.seller-url}") String baseUrl,
            @Value("${at.mcp.downstream.timeout:5s}") Duration timeout) {
        this.http = new JsonHttp(builder, "seller", baseUrl, timeout);
    }

    @Override
    public Downstream accountHealth(String orgId, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri("/v1/orgs/{orgId}/account-health", orgId));
    }
}
