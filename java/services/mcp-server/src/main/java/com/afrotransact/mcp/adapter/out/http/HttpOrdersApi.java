package com.afrotransact.mcp.adapter.out.http;

import java.time.Duration;
import java.util.Map;

import com.afrotransact.mcp.port.OrdersApi;
import com.afrotransact.mcp.port.Downstream;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** orders-service (port 8104): orders, seller order lists and seller shipment status changes. */
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
    public Downstream sellerOrders(String orgId, String status, int page, int size, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri(u -> {
            u.path("/v1/orders").queryParam("sellerOrgId", orgId).queryParam("page", page).queryParam("size", size);
            if (status != null && !status.isBlank()) {
                u.queryParam("status", status);
            }
            return u.build();
        }));
    }

    @Override
    public Downstream updateShipmentStatus(String orderId, String shipmentId, Map<String, Object> change,
                                           String bearerToken) {
        return http.call(bearerToken, c -> c.post().uri("/v1/orders/{orderId}/shipments/{shipmentId}/status",
                orderId, shipmentId).contentType(MediaType.APPLICATION_JSON).body(change));
    }
}
