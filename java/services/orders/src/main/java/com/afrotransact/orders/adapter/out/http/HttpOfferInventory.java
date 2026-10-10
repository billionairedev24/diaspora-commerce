package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.Optional;

import com.afrotransact.orders.port.OfferInventory;
import com.afrotransact.orders.port.ServiceTokenProvider;
import com.afrotransact.orders.port.UpstreamFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** offers-service {@code GET /v1/offers/{id}} with the orders-service token ({@code offers:read}). 404 = unknown. */
@Component
class HttpOfferInventory implements OfferInventory {

    private final Upstream offers;
    private final ServiceTokenProvider tokens;

    HttpOfferInventory(RestClient.Builder builder, @Value("${at.orders.upstreams.offers-url}") String offersUrl,
                       @Value("${at.orders.upstreams.timeout:5s}") Duration timeout, ServiceTokenProvider tokens) {
        this.offers = new Upstream("offers", builder, offersUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public Optional<String> inventorySku(String offerId) {
        if (offerId == null || offerId.isBlank()) {
            return Optional.empty();
        }
        JsonNode offer;
        try {
            offer = offers.call(c -> c.get().uri("/v1/offers/{id}", offerId)
                    .headers(h -> h.setBearerAuth(tokens.token()))
                    .retrieve().body(JsonNode.class));
        } catch (UpstreamFailure f) {
            if (f.status() == 404) {
                return Optional.empty();
            }
            throw f;
        }
        String sku = offer == null ? null : offer.path("inventorySku").asString(null);
        return sku == null || sku.isBlank() ? Optional.empty() : Optional.of(sku.trim());
    }
}
