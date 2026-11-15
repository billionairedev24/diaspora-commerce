package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import com.afrotransact.module.ModuleMode;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.port.ServiceTokenProvider;
import com.afrotransact.orders.port.ShipFromAddresses;
import com.afrotransact.orders.port.TaxQuoteClient.ShipFrom;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * {@link ShipFromAddresses} over inventory-service (a fulfilment centre's street address, for AfroTransact-held stock),
 * seller-service (a pickup location by id, or the organisation's default ship-from address). A 404, an upstream failure
 * or an address without a state and ZIP gives {@link Optional#empty()}: the tax quote then carries no origin for the
 * line, which tax-returns reports plainly instead of guessing one.
 */
@Component
@ConditionalOnModuleMode(caller = "orders", callee = "inventory", value = ModuleMode.HTTP)
class HttpShipFromAddresses implements ShipFromAddresses {

    private static final Logger LOG = LoggerFactory.getLogger(HttpShipFromAddresses.class);

    private final Upstream inventory;
    private final Upstream seller;
    private final ServiceTokenProvider tokens;

    HttpShipFromAddresses(RestClient.Builder builder, @Value("${at.orders.upstreams.inventory-url}") String inventoryUrl,
                          @Value("${at.orders.upstreams.seller-url}") String sellerUrl,
                          @Value("${at.orders.upstreams.timeout:5s}") Duration timeout, ServiceTokenProvider tokens) {
        this.inventory = new Upstream("inventory", builder, inventoryUrl, timeout);
        this.seller = new Upstream("seller", builder, sellerUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public Optional<ShipFrom> forShipment(FulfilmentType type, String sellerOrgId, String nodeId,
                                          String pickupLocationId) {
        if (nodeId != null && !nodeId.isBlank() && type != FulfilmentType.SELLER_FULFILLED) {
            return centre(nodeId);
        }
        if (pickupLocationId != null && !pickupLocationId.isBlank()) {
            return sellerAddress(seller, "/v1/ship-from-locations/{id}", pickupLocationId);
        }
        if (type == FulfilmentType.SELLER_FULFILLED && sellerOrgId != null && !sellerOrgId.isBlank()) {
            return sellerAddress(seller, "/v1/orgs/{id}/default-ship-from-location", sellerOrgId);
        }
        return Optional.empty();
    }

    private Optional<ShipFrom> centre(String nodeId) {
        JsonNode a = orNull(() -> inventory.call(c -> c.get().uri("/v1/nodes/{id}/address", nodeId)
                .headers(h -> h.setBearerAuth(tokens.token())).retrieve().body(JsonNode.class)), "inventory node " + nodeId);
        return a == null ? Optional.empty() : of(a, "region", "FULFILMENT_CENTRE");
    }

    private Optional<ShipFrom> sellerAddress(Upstream upstream, String path, String id) {
        JsonNode a = orNull(() -> upstream.call(c -> c.get().uri(path, id)
                .headers(h -> h.setBearerAuth(tokens.token())).retrieve().body(JsonNode.class)), "seller " + id);
        return a == null ? Optional.empty() : of(a, "state", "SELLER_LOCATION");
    }

    private static Optional<ShipFrom> of(JsonNode a, String stateField, String source) {
        String state = a.path(stateField).asString(null);
        String postal = a.path("postalCode").asString(null);
        if (state == null || state.isBlank() || postal == null || postal.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ShipFrom(a.path("line1").asString(null), a.path("city").asString(null), state, postal,
                a.path("country").asString(null), source));
    }

    private static JsonNode orNull(Supplier<JsonNode> call, String what) {
        try {
            return call.get();
        } catch (UpstreamFailure f) {
            if (f.status() != 404) {
                LOG.warn("ship-from of {} unavailable for the tax quote (HTTP {})", what, f.status());
            }
            return null;
        }
    }
}
