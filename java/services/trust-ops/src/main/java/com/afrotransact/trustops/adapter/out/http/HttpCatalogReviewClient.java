package com.afrotransact.trustops.adapter.out.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.trustops.config.TrustOpsProperties;
import com.afrotransact.trustops.domain.ModerationReasonCode;
import com.afrotransact.trustops.port.CatalogReviewClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * catalog-service {@code POST /v1/product-reviews/{id}/approve}, {@code /reject} and {@code /remove}
 * (contracts/openapi/catalog.yaml).
 */
@Component
class HttpCatalogReviewClient implements CatalogReviewClient {

    private static final String SERVICE = "catalog";
    private static final int MAX_NOTE = 500;

    private final Downstream downstream;
    private final RestClient client;

    HttpCatalogReviewClient(Downstream downstream, TrustOpsProperties props) {
        this.downstream = downstream;
        this.client = downstream.client(props.catalog().baseUrl(), null);
    }

    @Override
    public void approve(UUID reviewId, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("note", truncate(note));
        post("approve", reviewId, body);
    }

    @Override
    public void reject(UUID reviewId, ModerationReasonCode reasonCode, String note) {
        post("reject", reviewId, decision(reasonCode, note));
    }

    @Override
    public void remove(UUID reviewId, ModerationReasonCode reasonCode, String note) {
        post("remove", reviewId, decision(reasonCode, note));
    }

    private void post(String action, UUID reviewId, Map<String, Object> body) {
        downstream.call(SERVICE, client, c -> c.post().uri("/v1/product-reviews/{id}/" + action, reviewId)
                .headers(h -> downstream.headers(h, null)).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class), n -> n);
    }

    private static Map<String, Object> decision(ModerationReasonCode reasonCode, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reasonCode", reasonCode.name());
        body.put("note", truncate(note));
        return body;
    }

    private static String truncate(String note) {
        return note == null ? null : note.length() > MAX_NOTE ? note.substring(0, MAX_NOTE) : note;
    }
}
