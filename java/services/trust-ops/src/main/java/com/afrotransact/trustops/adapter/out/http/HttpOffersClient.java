package com.afrotransact.trustops.adapter.out.http;

import java.util.HashMap;
import java.util.Map;

import com.afrotransact.trustops.config.TrustOpsProperties;
import com.afrotransact.trustops.port.OffersClient;
import com.afrotransact.trustops.port.UpstreamException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * offers-service {@code PUT /v1/sellers/{orgId}/performance}. The PUT replaces every input, so the seller's current
 * rating is read first ({@code GET /v1/sellers/{orgId}/standing}) and sent back unchanged.
 */
@Component
class HttpOffersClient implements OffersClient {

    private static final String SERVICE = "offers";

    private final Downstream downstream;
    private final RestClient client;

    HttpOffersClient(Downstream downstream, TrustOpsProperties props) {
        this.downstream = downstream;
        this.client = downstream.client(props.offers().baseUrl(), null);
    }

    @Override
    public void pushPerformance(String orgId, Double onTimeRate, Double defectRate, boolean restricted) {
        Map<String, Object> body = new HashMap<>();
        body.put("rating", currentRating(orgId));
        body.put("onTimeRate", onTimeRate);
        body.put("defectRate", defectRate);
        body.put("restricted", restricted);
        downstream.call(SERVICE, client, c -> c.put().uri("/v1/sellers/{orgId}/performance", orgId)
                .headers(h -> downstream.headers(h, null)).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode.class), n -> n);
    }

    private Double currentRating(String orgId) {
        try {
            JsonNode standing = downstream.call(SERVICE, client, c -> c.get().uri("/v1/sellers/{orgId}/standing", orgId)
                    .headers(h -> downstream.headers(h, null)).retrieve().body(JsonNode.class), n -> n);
            JsonNode rating = standing.path("rating");
            return rating.isNumber() ? rating.asDouble() : null;
        } catch (UpstreamException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
    }
}
