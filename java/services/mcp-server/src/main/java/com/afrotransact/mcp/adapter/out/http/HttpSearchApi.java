package com.afrotransact.mcp.adapter.out.http;

import java.time.Duration;
import java.util.Map;

import com.afrotransact.mcp.port.SearchApi;
import com.afrotransact.mcp.port.Downstream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** search-service (port 8201): {@code GET /v1/search}. */
@Component
class HttpSearchApi implements SearchApi {

    private final JsonHttp http;

    HttpSearchApi(RestClient.Builder builder, @Value("${at.mcp.downstream.search-url}") String baseUrl,
            @Value("${at.mcp.downstream.timeout:5s}") Duration timeout) {
        this.http = new JsonHttp(builder, "search", baseUrl, timeout);
    }

    @Override
    public Downstream search(Map<String, Object> query, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri(u -> {
            u.path("/v1/search");
            query.forEach(u::queryParam);
            return u.build();
        }));
    }
}
