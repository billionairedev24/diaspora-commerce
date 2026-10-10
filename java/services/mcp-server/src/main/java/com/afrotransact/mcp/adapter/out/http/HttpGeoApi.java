package com.afrotransact.mcp.adapter.out.http;

import java.time.Duration;

import com.afrotransact.mcp.port.GeoApi;
import com.afrotransact.mcp.port.Downstream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** geo-service (port 8205): {@code GET /v1/zip/{zip}}. */
@Component
class HttpGeoApi implements GeoApi {

    private final JsonHttp http;

    HttpGeoApi(RestClient.Builder builder, @Value("${at.mcp.downstream.geo-url}") String baseUrl,
            @Value("${at.mcp.downstream.timeout:5s}") Duration timeout) {
        this.http = new JsonHttp(builder, "geo", baseUrl, timeout);
    }

    @Override
    public Downstream zip(String zip, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri("/v1/zip/{zip}", zip));
    }
}
