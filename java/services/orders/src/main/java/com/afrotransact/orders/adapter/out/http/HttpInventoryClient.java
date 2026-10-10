package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
                .body(Map.of("orderId", orderId, "lines", items.stream().map(HttpInventoryClient::line).toList()))
                .retrieve().body(JsonNode.class));
        List<Reserved> out = new ArrayList<>();
        if (reservation != null) {
            for (JsonNode l : reservation.path("lines")) {
                out.add(new Reserved(l.path("sku").asString(), l.path("nodeId").asString(null), l.path("qty").asInt(),
                        l.path("group").asString(null)));
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

    @Override
    public void commitGroups(String orderId, String shipmentId, List<String> groups) {
        post("/v1/reservations/{orderId}/groups/commit", orderId, Map.of("groups", groups),
                "commit-" + shipmentId);
    }

    @Override
    public void releaseGroups(String orderId, String shipmentId, List<String> groups, String reason) {
        post("/v1/reservations/{orderId}/groups/release", orderId, Map.of("groups", groups, "reason",
                reason == null ? "" : reason), "release-" + shipmentId);
    }

    private static Map<String, Object> line(Item i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku", i.sku());
        m.put("qty", i.qty());
        if (i.group() != null) {
            m.put("group", i.group());
        }
        return m;
    }

    private void post(String path, String orderId, Object body) {
        post(path, orderId, body, orderId);
    }

    private void post(String path, String orderId, Object body, String idempotencyKey) {
        inventory.call(c -> {
            RestClient.RequestBodySpec spec = c.post().uri(path, orderId).headers(h -> {
                h.setBearerAuth(tokens.token());
                h.set(IDEMPOTENCY_KEY, idempotencyKey);
            });
            if (body != null) {
                spec.body(body);
            }
            return spec.retrieve().toBodilessEntity();
        });
    }
}
