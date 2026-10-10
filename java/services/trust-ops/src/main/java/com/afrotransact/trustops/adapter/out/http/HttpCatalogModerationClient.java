package com.afrotransact.trustops.adapter.out.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.trustops.config.TrustOpsProperties;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.port.CatalogModerationClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * catalog-service {@code POST /v1/products/{id}/approve}, {@code /reject} and {@code /takedown}
 * (contracts/openapi/catalog.yaml).
 */
@Component
class HttpCatalogModerationClient implements CatalogModerationClient {

    private static final String SERVICE = "catalog";

    private final Downstream downstream;
    private final RestClient client;

    HttpCatalogModerationClient(Downstream downstream, TrustOpsProperties props) {
        this.downstream = downstream;
        this.client = downstream.client(props.catalog().baseUrl(), null);
    }

    @Override
    public void approve(UUID productId) {
        downstream.call(SERVICE, client, c -> c.post().uri("/v1/products/{id}/approve", productId)
                .headers(h -> downstream.headers(h, null)).retrieve().body(JsonNode.class), n -> n);
    }

    @Override
    public void reject(UUID productId, String note) {
        String text = note == null ? "" : note.length() > 1000 ? note.substring(0, 1000) : note;
        downstream.call(SERVICE, client, c -> c.post().uri("/v1/products/{id}/reject", productId)
                .headers(h -> downstream.headers(h, null)).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("note", text)).retrieve().body(JsonNode.class), n -> n);
    }

    @Override
    public void takeDown(UUID productId, ModerationReasonCode reasonCode, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reasonCode", reasonCode.name());
        body.put("note", note == null ? null : note.length() > 1000 ? note.substring(0, 1000) : note);
        downstream.call(SERVICE, client, c -> c.post().uri("/v1/products/{id}/takedown", productId)
                .headers(h -> downstream.headers(h, null)).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class), n -> n);
    }
}
