package com.afrotransact.fulfilment.adapter.out.http;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.afrotransact.fulfilment.port.CourierEvent;
import com.afrotransact.fulfilment.port.OrdersClient;
import com.afrotransact.fulfilment.port.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** orders-service (port 8104): transitions, courier events and tracking. */
@Component
class HttpOrdersClient implements OrdersClient {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final Upstream orders;
    private final ServiceTokenProvider tokens;

    HttpOrdersClient(RestClient.Builder builder, @Value("${at.fulfilment.upstreams.orders-url}") String baseUrl,
                     @Value("${at.fulfilment.upstreams.timeout:5s}") Duration timeout, ServiceTokenProvider tokens) {
        this.orders = new Upstream("orders", builder, baseUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public void transition(String shipmentId, String to, String reason, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", to);
        body.put("reason", reason);
        orders.call(c -> c.post().uri("/v1/shipments/{id}/transitions", shipmentId)
                .headers(h -> {
                    h.setBearerAuth(tokens.token());
                    h.set(IDEMPOTENCY_KEY, idempotencyKey);
                })
                .body(body).retrieve().toBodilessEntity());
    }

    @Override
    public void courierEvent(String shipmentId, CourierEvent e, String driverBearer) {
        Map<String, Object> proof = new LinkedHashMap<>();
        put(proof, "photoAssetId", e.photoAssetId());
        put(proof, "handoverMethod", e.handoverMethod());
        put(proof, "handoverCode", e.handoverCode());
        put(proof, "recipientName", e.recipientName());
        put(proof, "relationship", e.relationship());
        put(proof, "signatureAssetId", e.signatureAssetId());
        put(proof, "safePlace", e.safePlace());
        put(proof, "failureReason", e.failureReason());
        put(proof, "latitude", e.latitude());
        put(proof, "longitude", e.longitude());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", e.eventId());
        body.put("status", e.status());
        put(body, "occurredAt", e.occurredAt() == null ? null : e.occurredAt().toString());
        put(body, "note", e.note() == null || e.note().length() <= 500 ? e.note() : e.note().substring(0, 500));
        // Actor when relayed with the service token (orders-service gap: see README).
        put(body, "driverId", e.driverId());
        body.put("proof", proof);
        String bearer = driverBearer == null || driverBearer.isBlank() ? tokens.token() : driverBearer;
        orders.call(c -> c.post().uri("/v1/shipments/{id}/courier-events", shipmentId)
                .headers(h -> h.setBearerAuth(bearer))
                .body(body).retrieve().toBodilessEntity());
    }

    @Override
    public void attachTracking(String shipmentId, String carrier, String trackingNumber) {
        orders.call(c -> c.post().uri("/v1/shipments/{id}/tracking", shipmentId)
                .headers(h -> h.setBearerAuth(tokens.token()))
                .body(Map.of("carrier", carrier, "trackingNumber", trackingNumber))
                .retrieve().toBodilessEntity());
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
