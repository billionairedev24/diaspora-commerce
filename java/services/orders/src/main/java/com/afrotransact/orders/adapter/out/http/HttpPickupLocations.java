package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import com.afrotransact.orders.port.PickupLocations;
import com.afrotransact.orders.port.ServiceTokenProvider;
import com.afrotransact.orders.port.UpstreamFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * offers-service (port 8102) {@code GET /v1/public/offers/{offerId}} → {@code shipFromLocationId}, then
 * seller-service (port 8106) {@code GET /v1/ship-from-locations/{id}} with the orders-service token
 * ({@code sellers:ship-from.read}). 404 = no location; 5xx / I/O = retryable {@link UpstreamFailure}.
 */
@Component
class HttpPickupLocations implements PickupLocations {

    private final Upstream offers;
    private final Upstream seller;
    private final ServiceTokenProvider tokens;

    HttpPickupLocations(RestClient.Builder builder, @Value("${at.orders.upstreams.offers-url}") String offersUrl,
                        @Value("${at.orders.upstreams.seller-url}") String sellerUrl,
                        @Value("${at.orders.upstreams.timeout:5s}") Duration timeout, ServiceTokenProvider tokens) {
        this.offers = new Upstream("offers", builder, offersUrl, timeout);
        this.seller = new Upstream("seller", builder, sellerUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public Optional<PickupLocation> forOffer(String offerId) {
        if (offerId == null || offerId.isBlank()) {
            return Optional.empty();
        }
        JsonNode detail = notFoundIsEmpty(() -> offers.call(c -> c.get().uri("/v1/public/offers/{id}", offerId)
                .retrieve().body(JsonNode.class)));
        // PublicOfferDetail: {offer: {…, shipFromLocationId}, buyable, notEligible}
        JsonNode offer = detail == null ? null : detail.path("offer").isObject() ? detail.path("offer") : detail;
        String locationId = offer == null ? null : offer.path("shipFromLocationId").asString(null);
        if (locationId == null || locationId.isBlank()) {
            return Optional.empty();
        }
        JsonNode l = notFoundIsEmpty(() -> seller.call(c -> c.get().uri("/v1/ship-from-locations/{id}", locationId)
                .headers(h -> h.setBearerAuth(tokens.token()))
                .retrieve().body(JsonNode.class)));
        if (l == null) {
            return Optional.empty();
        }
        JsonNode position = l.path("position");
        return Optional.of(new PickupLocation(locationId, l.path("orgId").asString(null),
                l.path("afrotransactPickup").path("enabled").asBoolean(false),
                position.path("lat").isNumber() ? position.path("lat").asDouble() : null,
                position.path("lng").isNumber() ? position.path("lng").asDouble() : null));
    }

    private static JsonNode notFoundIsEmpty(Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (UpstreamFailure f) {
            if (f.status() == 404) {
                return null;
            }
            throw f;
        }
    }
}
