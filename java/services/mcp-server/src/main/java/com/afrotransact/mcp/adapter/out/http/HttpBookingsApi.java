package com.afrotransact.mcp.adapter.out.http;

import java.time.Duration;

import com.afrotransact.mcp.port.BookingsApi;
import com.afrotransact.mcp.port.Downstream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** bookings-service (port 8109): {@code GET /v1/services/search} (UNAVAILABLE until deployed). */
@Component
class HttpBookingsApi implements BookingsApi {

    private final JsonHttp http;

    HttpBookingsApi(RestClient.Builder builder, @Value("${at.mcp.downstream.bookings-url}") String baseUrl,
            @Value("${at.mcp.downstream.timeout:5s}") Duration timeout) {
        this.http = new JsonHttp(builder, "bookings", baseUrl, timeout);
    }

    @Override
    public Downstream findServices(String query, String zip, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri(u -> {
            u.path("/v1/services/search").queryParam("q", query).queryParam("size", 10);
            if (zip != null && !zip.isBlank()) {
                u.queryParam("zip", zip);
            }
            return u.build();
        }));
    }
}
