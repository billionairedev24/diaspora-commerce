package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.afrotransact.orders.port.InventoryClient;
import com.afrotransact.orders.port.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** inventory-service (port 8103) reservations; Idempotency-Key = order id on every call. */
@Component
class HttpInventoryClient implements InventoryClient {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final Upstream inventory;
    private final ServiceTokenProvider tokens;

    HttpInventoryClient(RestClient.Builder builder, @Value("${at.orders.upstreams.inventory-url}") String baseUrl,
                        @Value("${at.orders.upstreams.timeout:5s}") Duration timeout, ServiceTokenProvider tokens) {
        this.inventory = new Upstream("inventory", builder, baseUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public List<Reserved> reserve(String orderId, List<Item> items) {
        JsonNode reservation = inventory.call(c -> c.post().uri("/v1/reservations")
                .headers(h -> {
                    h.setBearerAuth(tokens.token());
                    h.set(IDEMPOTENCY_KEY, orderId);
                })
                .body(Map.of("orderId", orderId, "lines", items.stream()
                        .map(i -> Map.of("sku", i.sku(), "qty", i.qty())).toList()))
                .retrieve().body(JsonNode.class));
        List<Reserved> out = new ArrayList<>();
        if (reservation != null) {
            for (JsonNode l : reservation.path("lines")) {
                out.add(new Reserved(l.path("sku").asString(), l.path("nodeId").asString(null), l.path("qty").asInt()));
            }
        }
        return out;
    }

    @Override
    public void confirm(String orderId) {
        post("/v1/reservations/{orderId}/confirm", orderId, null);
    }

    @Override
    public void commit(String orderId) {
        post("/v1/reservations/{orderId}/commit", orderId, null);
    }

    @Override
    public void release(String orderId, String reason) {
        post("/v1/reservations/{orderId}/release", orderId, Map.of("reason", reason == null ? "" : reason));
    }

    private void post(String path, String orderId, Object body) {
        inventory.call(c -> {
            RestClient.RequestBodySpec spec = c.post().uri(path, orderId).headers(h -> {
                h.setBearerAuth(tokens.token());
                h.set(IDEMPOTENCY_KEY, orderId);
            });
            if (body != null) {
                spec.body(body);
            }
            return spec.retrieve().toBodilessEntity();
        });
    }
}
