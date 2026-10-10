package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.orders.port.PromiseClient;
import com.afrotransact.orders.port.UpstreamFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** promise-service (port 8204) {@code POST /v1/promises {zip, items}} → {@code {promises:[{atin, days}]}}. */
@Component
class HttpPromiseClient implements PromiseClient {

    private static final Logger LOG = LoggerFactory.getLogger(HttpPromiseClient.class);

    private final Upstream promise;

    HttpPromiseClient(RestClient.Builder builder, @Value("${at.orders.upstreams.promise-url}") String baseUrl,
                      @Value("${at.orders.upstreams.promise-timeout:800ms}") Duration timeout) {
        this.promise = new Upstream("promise", builder, baseUrl, timeout);
    }

    @Override
    public Map<String, Integer> days(String zip, List<Item> items) {
        try {
            JsonNode body = promise.call(c -> c.post().uri("/v1/promises")
                    .body(Map.of("zip", zip == null ? "" : zip, "items", items))
                    .retrieve().body(JsonNode.class));
            Map<String, Integer> out = new LinkedHashMap<>();
            if (body != null) {
                for (JsonNode p : body.path("promises")) {
                    if (p.hasNonNull("atin") && p.hasNonNull("days")) {
                        out.put(p.path("atin").asString(), p.path("days").asInt());
                    }
                }
            }
            return out;
        } catch (UpstreamFailure f) {
            LOG.debug("promise service unavailable ({}); using stored estimates", f.code());
            return Map.of();
        }
    }
}
