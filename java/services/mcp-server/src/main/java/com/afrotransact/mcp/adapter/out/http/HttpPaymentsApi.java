package com.afrotransact.mcp.adapter.out.http;

import java.time.Duration;

import com.afrotransact.mcp.port.PaymentsApi;
import com.afrotransact.mcp.port.Downstream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** payments-service (port 8105): staff payment split of an order. */
@Component
class HttpPaymentsApi implements PaymentsApi {

    private final JsonHttp http;

    HttpPaymentsApi(RestClient.Builder builder, @Value("${at.mcp.downstream.payments-url}") String baseUrl,
            @Value("${at.mcp.downstream.timeout:5s}") Duration timeout) {
        this.http = new JsonHttp(builder, "payments", baseUrl, timeout);
    }

    @Override
    public Downstream orderSplit(String orderId, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri("/v1/orders/{orderId}/payment-split", orderId));
    }

    @Override
    public Downstream payoutBalance(String orgId, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri(u -> u.path("/v1/payouts/balance").queryParam("orgId", orgId)
                .build()));
    }

    @Override
    public Downstream payoutRequests(String orgId, int limit, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri(u -> u.path("/v1/payouts/requests").queryParam("orgId", orgId)
                .queryParam("limit", limit).build()));
    }
}
