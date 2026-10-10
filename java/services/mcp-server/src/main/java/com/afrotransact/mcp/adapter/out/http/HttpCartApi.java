package com.afrotransact.mcp.adapter.out.http;

import java.time.Duration;

import com.afrotransact.mcp.port.CartApi;
import com.afrotransact.mcp.port.Downstream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** cart-service (port 8203): {@code GET /v1/cart} as the shopper. */
@Component
class HttpCartApi implements CartApi {

    private final JsonHttp http;

    HttpCartApi(RestClient.Builder builder, @Value("${at.mcp.downstream.cart-url}") String baseUrl,
            @Value("${at.mcp.downstream.timeout:5s}") Duration timeout) {
        this.http = new JsonHttp(builder, "cart", baseUrl, timeout);
    }

    @Override
    public Downstream cart(String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri("/v1/cart"));
    }
}
