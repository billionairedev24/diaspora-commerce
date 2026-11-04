package com.afrotransact.mcp.adapter.out.http;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

import com.afrotransact.mcp.port.CatalogApi;
import com.afrotransact.mcp.port.Downstream;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** catalog-service (port 8101): public product pages, seller listings, moderation queue, category requests. */
@Component
class HttpCatalogApi implements CatalogApi {

    private final JsonHttp http;

    HttpCatalogApi(RestClient.Builder builder, @Value("${at.mcp.downstream.catalog-url}") String baseUrl,
            @Value("${at.mcp.downstream.timeout:5s}") Duration timeout) {
        this.http = new JsonHttp(builder, "catalog", baseUrl, timeout);
    }

    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F-]{36}");

    @Override
    public Downstream publicProduct(String atin, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri("/v1/public/products/{atin}", atin));
    }

    @Override
    public Downstream publicVariant(String atin, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri("/v1/public/variants/{atin}", atin));
    }

    @Override
    public Downstream products(String orgId, String status, String query, int page, int size, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri(u -> {
            u.path("/v1/products").queryParam("page", page).queryParam("size", size);
            if (orgId != null) {
                u.queryParam("orgId", orgId);
            }
            if (status != null) {
                u.queryParam("status", status);
            }
            if (query != null && !query.isBlank()) {
                u.queryParam("q", query);
            }
            return u.build();
        }));
    }

    @Override
    public Downstream product(String idOrAtin, String bearerToken) {
        if (UUID.matcher(idOrAtin).matches()) {
            return http.call(bearerToken, c -> c.get().uri("/v1/products/{id}", idOrAtin));
        }
        return http.call(bearerToken, c -> c.get().uri("/v1/products/atin/{atin}", idOrAtin));
    }

    @Override
    public Downstream categoryRequests(String status, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri(u -> {
            u.path("/v1/category-requests");
            if (status != null && !status.isBlank()) {
                u.queryParam("status", status);
            }
            return u.build();
        }));
    }

    @Override
    public Downstream requestCategory(Map<String, Object> request, String bearerToken) {
        return http.call(bearerToken, c -> c.post().uri("/v1/category-requests")
                .contentType(MediaType.APPLICATION_JSON).body(request));
    }
}
