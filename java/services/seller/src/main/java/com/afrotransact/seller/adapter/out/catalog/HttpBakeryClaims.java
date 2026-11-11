package com.afrotransact.seller.adapter.out.catalog;

import java.time.Duration;
import java.util.List;

import com.afrotransact.seller.adapter.out.usage.ServiceTokens;
import com.afrotransact.seller.port.BakeryClaims;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** catalog-service {@code GET /v1/internal/bakery-claims} with seller's service token (scope catalog:bakery-claims.read). */
public class HttpBakeryClaims implements BakeryClaims {

    private final RestClient catalog;
    private final ServiceTokens tokens;

    public HttpBakeryClaims(String baseUrl, Duration timeout, ServiceTokens tokens) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.catalog = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = tokens;
    }

    @Override
    public List<String> organisations() {
        String token = tokens.enabled() ? tokens.token() : null;
        JsonNode body = catalog.get().uri("/v1/internal/bakery-claims").headers(h -> {
            if (token != null) {
                h.setBearerAuth(token);
            }
        }).retrieve().body(JsonNode.class);
        if (body == null || !body.path("organisations").isArray()) {
            return List.of();
        }
        return body.path("organisations").valueStream().map(n -> n.asString("")).filter(s -> !s.isBlank()).toList();
    }
}
