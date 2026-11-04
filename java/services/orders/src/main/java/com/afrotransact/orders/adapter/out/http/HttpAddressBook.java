package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.Optional;

import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.port.AddressBook;
import com.afrotransact.platform.security.ActingAsActorResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** identity-service (port 8100) {@code GET /v1/me/addresses} with the customer's token. */
@Component
class HttpAddressBook implements AddressBook {

    private final Upstream identity;

    HttpAddressBook(RestClient.Builder builder, @Value("${at.orders.upstreams.identity-url}") String baseUrl,
                    @Value("${at.orders.upstreams.timeout:5s}") Duration timeout) {
        this.identity = new Upstream("identity", builder, baseUrl, timeout);
    }

    @Override
    public Optional<ShipTo> find(String customerBearer, String addressId) {
        JsonNode list = identity.call(c -> c.get().uri("/v1/me/addresses")
                .headers(h -> {
                    if (customerBearer != null && !customerBearer.isBlank()) {
                        h.setBearerAuth(customerBearer);
                        // The shopper's token may carry their seller organisation: they still check out as themselves.
                        h.set(ActingAsActorResolver.HEADER, ActingAsActorResolver.CUSTOMER);
                    }
                })
                .retrieve().body(JsonNode.class));
        if (list == null) {
            return Optional.empty();
        }
        for (JsonNode a : list) {
            if (addressId.equals(a.path("id").asString())) {
                return Optional.of(new ShipTo(addressId, a.path("fullName").asString(null),
                        a.path("phone").asString(null), a.path("line1").asString(null), a.path("line2").asString(null),
                        a.path("city").asString(null), a.path("state").asString(null),
                        a.path("postalCode").asString(null), a.path("country").asString("US"),
                        a.path("instructions").asString(null), number(a, "latitude"), number(a, "longitude")));
            }
        }
        return Optional.empty();
    }

    private static Double number(JsonNode a, String field) {
        JsonNode n = a.path(field);
        return n.isNumber() ? n.asDouble() : null;
    }
}
