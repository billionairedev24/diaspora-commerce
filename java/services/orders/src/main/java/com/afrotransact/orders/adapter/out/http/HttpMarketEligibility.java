package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.Optional;

import com.afrotransact.orders.port.MarketEligibility;
import com.afrotransact.orders.port.UpstreamFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** content-service (port 8112) {@code GET /v1/markets/eligibility?country=&state=&zip=} (public). */
@Component
class HttpMarketEligibility implements MarketEligibility {

    private static final Logger LOG = LoggerFactory.getLogger(HttpMarketEligibility.class);

    private final Upstream content;

    HttpMarketEligibility(RestClient.Builder builder, @Value("${at.orders.upstreams.content-url}") String baseUrl,
                          @Value("${at.orders.upstreams.timeout:5s}") Duration timeout) {
        this.content = new Upstream("content", builder, baseUrl, timeout);
    }

    @Override
    public Optional<Result> check(String country, String state, String zip) {
        try {
            JsonNode e = content.call(c -> c.get().uri(u -> {
                u.path("/v1/markets/eligibility").queryParam("country", country == null ? "US" : country);
                if (state != null && !state.isBlank()) {
                    u.queryParam("state", state);
                }
                if (zip != null && !zip.isBlank()) {
                    u.queryParam("zip", zip);
                }
                return u.build();
            }).retrieve().body(JsonNode.class));
            if (e == null || !e.has("deliveryAllowed")) {
                return Optional.empty();
            }
            return Optional.of(new Result(e.path("deliveryAllowed").asBoolean(true), e.path("reason").asString(null),
                    e.path("message").asString(null)));
        } catch (UpstreamFailure f) {
            LOG.warn("markets eligibility unavailable ({}); allowing checkout", f.code());
            return Optional.empty();
        }
    }
}
