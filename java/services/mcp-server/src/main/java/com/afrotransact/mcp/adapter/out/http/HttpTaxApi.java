package com.afrotransact.mcp.adapter.out.http;

import java.time.Duration;

import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.TaxApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** tax-returns (port 8111): the review queue and the shadow report. */
@Component
class HttpTaxApi implements TaxApi {

    private final JsonHttp http;

    HttpTaxApi(RestClient.Builder builder, @Value("${at.mcp.downstream.tax-url}") String baseUrl,
            @Value("${at.mcp.downstream.timeout:5s}") Duration timeout) {
        this.http = new JsonHttp(builder, "tax-returns", baseUrl, timeout);
    }

    @Override
    public Downstream reviewQueue(String status, String query, int page, int size, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri(u -> {
            u.path("/v1/tax/variant-classifications").queryParam("status", status).queryParam("page", page)
                    .queryParam("size", size);
            if (query != null && !query.isBlank()) {
                u.queryParam("q", query);
            }
            return u.build();
        }));
    }

    @Override
    public Downstream shadowReport(String from, String to, String provider, int size, String bearerToken) {
        return http.call(bearerToken, c -> c.get().uri(u -> {
            u.path("/v1/tax/shadow/report").queryParam("from", from).queryParam("to", to).queryParam("size", size)
                    .queryParam("onlyDifferences", true);
            if (provider != null && !provider.isBlank()) {
                u.queryParam("provider", provider);
            }
            return u.build();
        }));
    }
}
