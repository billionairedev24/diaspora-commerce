package com.afrotransact.mcp.adapter.out.http;

import java.time.Duration;

import com.afrotransact.mcp.port.ApplicationsApi;
import com.afrotransact.mcp.port.Downstream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** seller-service (port 8106): onboarding applications review queue and detail (masked view). */
@Component
class HttpApplicationsApi implements ApplicationsApi {

    private final JsonHttp http;

    HttpApplicationsApi(RestClient.Builder builder, @Value("${at.mcp.downstream.seller-url}") String baseUrl,
            @Value("${at.mcp.downstream.timeout:5s}") Duration timeout) {
        this.http = new JsonHttp(builder, "seller", baseUrl, timeout);
    }

    @Override
    public Downstream queue(String status, int page, int size, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri(u -> u.path("/v1/applications").queryParam("status", status)
                .queryParam("page", page).queryParam("size", size).build()));
    }

    @Override
    public Downstream application(String applicationId, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri(u -> u.path("/v1/applications/{id}")
                .queryParam("pii", "MASKED").build(applicationId)));
    }
}
