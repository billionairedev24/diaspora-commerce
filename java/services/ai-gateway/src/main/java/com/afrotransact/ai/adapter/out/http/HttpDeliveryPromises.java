package com.afrotransact.ai.adapter.out.http;

import java.time.Duration;
import java.util.Optional;

import com.afrotransact.ai.port.DeliveryPromises;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** promise-service {@code GET /v1/promises?atin=&zip=} (port 8204); empty when the service does not answer. */
@Component
class HttpDeliveryPromises implements DeliveryPromises {

    private static final Logger LOG = LoggerFactory.getLogger(HttpDeliveryPromises.class);

    private final RestClient client;

    HttpDeliveryPromises(RestClient.Builder builder, @Value("${at.ai.downstream.promise-url}") String baseUrl,
                         @Value("${at.ai.downstream.timeout:5s}") Duration timeout) {
        this.client = Downstream.client(builder, baseUrl, timeout);
    }

    @Override
    public Optional<Promise> promise(String atin, String zip, String bearerToken) {
        try {
            JsonNode p = client.get()
                    .uri(u -> u.path("/v1/promises").queryParam("atin", atin).queryParam("zip", zip).build())
                    .headers(h -> Downstream.relay(h, bearerToken))
                    .retrieve().body(JsonNode.class);
            if (p == null) {
                return Optional.empty();
            }
            return Optional.of(new Promise(p.path("deliverable").asBoolean(true), Downstream.text(p, "label"),
                    Downstream.text(p, "earliest"), Downstream.text(p, "latest"), Downstream.text(p, "method")));
        } catch (RestClientException e) {
            LOG.debug("promise service unavailable: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
