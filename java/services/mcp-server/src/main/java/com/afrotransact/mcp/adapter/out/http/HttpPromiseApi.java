package com.afrotransact.mcp.adapter.out.http;

import java.time.Duration;

import com.afrotransact.mcp.port.PromiseApi;
import com.afrotransact.mcp.port.Downstream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** promise-service (port 8204): {@code GET /v1/promises?atin=&zip=} (UNAVAILABLE until deployed). */
@Component
class HttpPromiseApi implements PromiseApi {

    private final JsonHttp http;

    HttpPromiseApi(RestClient.Builder builder, @Value("${at.mcp.downstream.promise-url}") String baseUrl,
            @Value("${at.mcp.downstream.timeout:5s}") Duration timeout) {
        this.http = new JsonHttp(builder, "promise", baseUrl, timeout);
    }

    @Override
    public Downstream promise(String atin, String zip, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri(u -> u.path("/v1/promises").queryParam("atin", atin)
                .queryParam("zip", zip).build()));
    }
}
