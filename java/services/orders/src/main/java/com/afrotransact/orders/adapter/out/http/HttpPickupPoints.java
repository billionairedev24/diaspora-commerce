package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.Optional;

import com.afrotransact.orders.port.PickupPoints;
import com.afrotransact.orders.port.UpstreamFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * carrier-gateway (port 8209) {@code GET /v1/pickup-locations/{id}} (public). 404 = unknown point; 5xx / I/O =
 * retryable {@link UpstreamFailure}.
 */
@Component
class HttpPickupPoints implements PickupPoints {

    private final Upstream carrier;

    HttpPickupPoints(RestClient.Builder builder, @Value("${at.orders.upstreams.carrier-gateway-url}") String url,
                     @Value("${at.orders.upstreams.timeout:5s}") Duration timeout) {
        this.carrier = new Upstream("carrier-gateway", builder, url, timeout);
    }

    @Override
    public Optional<PickupPoint> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        JsonNode l;
        try {
            l = carrier.call(c -> c.get().uri("/v1/pickup-locations/{id}", id.trim()).retrieve().body(JsonNode.class));
        } catch (UpstreamFailure f) {
            if (f.status() == 404 || f.status() == 400) {
                return Optional.empty();
            }
            throw f;
        }
        if (l == null || !l.hasNonNull("id")) {
            return Optional.empty();
        }
        return Optional.of(new PickupPoint(l.path("id").asString(id), l.path("kind").asString(""),
                l.path("ownerOrg").asString(""), l.path("name").asString(""), l.path("active").asBoolean(false)));
    }
}
