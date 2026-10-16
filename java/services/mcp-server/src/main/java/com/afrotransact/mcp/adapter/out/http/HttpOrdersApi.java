package com.afrotransact.mcp.adapter.out.http;

import java.time.Duration;
import java.util.Map;

import com.afrotransact.mcp.port.OrdersApi;
import com.afrotransact.mcp.port.Downstream;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** orders-service (port 8104): orders, the seller shipment queue, shipment steps and tracking. */
@Component
class HttpOrdersApi implements OrdersApi {

    private final JsonHttp http;

    HttpOrdersApi(RestClient.Builder builder, @Value("${at.mcp.downstream.orders-url}") String baseUrl,
            @Value("${at.mcp.downstream.timeout:5s}") Duration timeout) {
        this.http = new JsonHttp(builder, "orders", baseUrl, timeout);
    }

    @Override
    public Downstream order(String orderId, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri("/v1/orders/{id}", orderId));
    }

    @Override
    public Downstream sellerShipments(String status, String cursor, int limit, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri(u -> {
            u.path("/v1/seller/shipments").queryParam("limit", limit);
            if (status != null && !status.isBlank()) {
                u.queryParam("status", status);
            }
            if (cursor != null && !cursor.isBlank()) {
                u.queryParam("cursor", cursor);
            }
            return u.build();
        }));
    }

    @Override
    public Downstream transitionShipment(String shipmentId, Map<String, Object> body, String bearerToken) {
        return http.call(bearerToken, c -> c.post().uri("/v1/shipments/{id}/transitions", shipmentId)
                .contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @Override
    public Downstream attachTracking(String shipmentId, String carrier, String trackingNumber, String bearerToken) {
        return http.call(bearerToken, c -> c.post().uri("/v1/shipments/{id}/tracking", shipmentId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("carrier", carrier, "trackingNumber", trackingNumber)));
    }
}
