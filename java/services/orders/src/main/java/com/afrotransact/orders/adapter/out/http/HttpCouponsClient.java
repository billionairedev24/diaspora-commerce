package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.orders.port.CouponsClient;
import com.afrotransact.orders.port.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** offers-service coupons (port 8102) with the orders-service client-credentials token (SERVICE principal). */
@Component
class HttpCouponsClient implements CouponsClient {

    private final Upstream offers;
    private final ServiceTokenProvider tokens;

    HttpCouponsClient(RestClient.Builder builder, @Value("${at.orders.upstreams.offers-url}") String baseUrl,
                      @Value("${at.orders.upstreams.timeout:5s}") Duration timeout, ServiceTokenProvider tokens) {
        this.offers = new Upstream("offers", builder, baseUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public Quote validate(Request request) {
        JsonNode q = offers.call(c -> c.post().uri("/v1/coupons/validate")
                .headers(h -> h.setBearerAuth(tokens.token()))
                .body(body(request)).retrieve().body(JsonNode.class));
        if (q == null) {
            return new Quote(false, "NOT_FOUND", "offers sent no quote", 0, 0, 0, null, List.of());
        }
        return new Quote(q.path("applicable").asBoolean(false), q.path("rejection").asString(null),
                q.path("message").asString(null), q.path("discountMinor").asLong(0),
                q.path("shippingDiscountMinor").asLong(0), q.path("totalDiscountMinor").asLong(0),
                q.path("funding").asString(null), lines(q));
    }

    @Override
    public Redemption redeem(String idempotencyKey, Request request) {
        return redemption(offers.call(c -> c.post().uri("/v1/coupons/redemptions")
                .headers(h -> {
                    h.setBearerAuth(tokens.token());
                    h.set("Idempotency-Key", idempotencyKey);
                })
                .body(body(request)).retrieve().body(JsonNode.class)));
    }

    @Override
    public Redemption release(String redemptionId) {
        return redemption(offers.call(c -> c.post().uri("/v1/coupons/redemptions/{id}/release", redemptionId)
                .headers(h -> h.setBearerAuth(tokens.token()))
                .retrieve().body(JsonNode.class)));
    }

    private static Map<String, Object> body(Request r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", r.code());
        body.put("customerId", r.customerId());
        if (r.orderId() != null) {
            body.put("orderId", r.orderId());
        }
        body.put("currency", r.currency());
        body.put("lines", r.lines().stream().map(l -> Map.of("offerId", l.offerId(), "quantity", l.quantity()))
                .toList());
        body.put("shippingMinor", r.shippingMinor());
        body.put("firstOrder", r.firstOrder());
        return body;
    }

    private static Redemption redemption(JsonNode r) {
        if (r == null) {
            throw new IllegalStateException("offers sent an empty redemption");
        }
        return new Redemption(r.path("id").asString(), r.path("couponId").asString(null),
                r.path("code").asString(null), r.path("status").asString(null), r.path("discountMinor").asLong(0),
                r.path("shippingDiscountMinor").asLong(0), r.path("totalDiscountMinor").asLong(0),
                r.path("funding").asString(null), r.path("fundedByOrg").asString(null), lines(r));
    }

    private static List<LineDiscount> lines(JsonNode node) {
        List<LineDiscount> out = new ArrayList<>();
        for (JsonNode l : node.path("lines")) {
            out.add(new LineDiscount(l.path("lineRef").asString(), l.path("sellerOrgId").asString(null),
                    l.path("discountMinor").asLong(0)));
        }
        return out;
    }
}
