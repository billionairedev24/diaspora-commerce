package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.orders.port.CartClient;
import com.afrotransact.orders.port.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** cart-service (port 8203): {@code GET /v1/cart} with the customer's token, {@code POST /v1/carts/{id}/ordered}. */
@Component
class HttpCartClient implements CartClient {

    private final Upstream cart;
    private final ServiceTokenProvider tokens;

    HttpCartClient(RestClient.Builder builder, @Value("${at.orders.upstreams.cart-url}") String baseUrl,
                   @Value("${at.orders.upstreams.timeout:5s}") Duration timeout, ServiceTokenProvider tokens) {
        this.cart = new Upstream("cart", builder, baseUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public Snapshot cart(String customerBearer) {
        JsonNode view = cart.call(c -> c.get().uri("/v1/cart")
                .headers(h -> {
                    if (customerBearer != null && !customerBearer.isBlank()) {
                        h.setBearerAuth(customerBearer);
                    }
                })
                .retrieve().body(JsonNode.class));
        if (view == null) {
            return new Snapshot("USD", false, 0, List.of());
        }
        String currency = view.path("currency").asString("USD");
        List<Item> items = new ArrayList<>();
        for (JsonNode pkg : view.path("packages")) {
            for (JsonNode l : pkg.path("lines")) {
                items.add(new Item(l.path("variantAtin").asString(), l.path("productAtin").asString(null),
                        l.path("title").asString(null), l.path("imageUrl").asString(null),
                        text(l, "sellerOrgId", pkg.path("sellerOrgId").asString(null)),
                        text(l, "sellerName", pkg.path("sellerName").asString(null)),
                        l.path("sellerKind").asString(null),
                        text(l, "fulfilment", pkg.path("fulfilment").asString("SELLER")),
                        l.path("priceMinor").asLong(), l.path("currency").asString(currency),
                        l.path("quantity").asInt(), l.path("unavailable").asBoolean(false),
                        l.path("offerId").asString(null)));
            }
        }
        Map<String, String> preorder = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : view.path("preorderFulfilment").properties()) {
            preorder.put(e.getKey(), e.getValue().asString(""));
        }
        for (JsonNode pkg : view.path("packages")) {
            JsonNode p = pkg.path("preorder");
            if (p.isObject() && !p.path("windowId").asString("").isEmpty()
                    && !p.path("fulfilment").asString("").isEmpty()) {
                preorder.putIfAbsent(p.path("windowId").asString(), p.path("fulfilment").asString());
            }
        }
        return new Snapshot(currency, view.path("checkoutable").asBoolean(false), view.path("version").asLong(0),
                items, preorder);
    }

    @Override
    public void clearOrdered(String customerId, List<String> variantAtins) {
        cart.call(c -> c.post().uri("/v1/carts/{customerId}/ordered", customerId)
                .headers(h -> h.setBearerAuth(tokens.token()))
                .body(Map.of("variantAtins", variantAtins))
                .retrieve().toBodilessEntity());
    }

    private static String text(JsonNode node, String field, String fallback) {
        String v = node.path(field).asString("");
        return v.isEmpty() ? fallback : v;
    }
}
