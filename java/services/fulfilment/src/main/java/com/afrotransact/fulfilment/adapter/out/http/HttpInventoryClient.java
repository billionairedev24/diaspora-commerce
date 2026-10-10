package com.afrotransact.fulfilment.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.port.InventoryClient;
import com.afrotransact.fulfilment.port.ServiceTokenProvider;
import com.afrotransact.fulfilment.port.UpstreamFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** inventory-service (port 8103): reservation of an order (node and FEFO lots per SKU). */
@Component
class HttpInventoryClient implements InventoryClient {

    private final Upstream inventory;
    private final ServiceTokenProvider tokens;

    HttpInventoryClient(RestClient.Builder builder,
                        @Value("${at.fulfilment.upstreams.inventory-url}") String baseUrl,
                        @Value("${at.fulfilment.upstreams.timeout:5s}") Duration timeout,
                        ServiceTokenProvider tokens) {
        this.inventory = new Upstream("inventory", builder, baseUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public Optional<List<ReservedLine>> reservation(String orderId) {
        JsonNode r;
        try {
            r = inventory.call(c -> c.get().uri("/v1/reservations/{orderId}", orderId)
                    .headers(h -> h.setBearerAuth(tokens.token()))
                    .retrieve().body(JsonNode.class));
        } catch (UpstreamFailure f) {
            if (f.status() == 404) {
                return Optional.empty();
            }
            throw f;
        }
        if (r == null) {
            return Optional.empty();
        }
        List<ReservedLine> out = new ArrayList<>();
        for (JsonNode l : r.path("lines")) {
            List<String> lots = new ArrayList<>();
            for (JsonNode a : l.path("allocations")) {
                String lot = a.path("lotId").asString(null);
                if (lot != null) {
                    lots.add(lot);
                }
            }
            out.add(new ReservedLine(l.path("sku").asString(null), l.path("nodeId").asString(null),
                    l.path("qty").asInt(), lots));
        }
        return Optional.of(out);
    }
}
