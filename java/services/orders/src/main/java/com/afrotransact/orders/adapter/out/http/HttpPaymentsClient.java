package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.orders.port.ServiceTokenProvider;
import com.afrotransact.orders.port.UpstreamFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** payments-service (port 8105) as the orders SERVICE principal (payments:service). */
@Component
class HttpPaymentsClient implements PaymentsClient {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final Upstream payments;
    private final ServiceTokenProvider tokens;

    HttpPaymentsClient(RestClient.Builder builder, @Value("${at.orders.upstreams.payments-url}") String baseUrl,
                       @Value("${at.orders.upstreams.timeout:5s}") Duration timeout, ServiceTokenProvider tokens) {
        this.payments = new Upstream("payments", builder, baseUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public Intent createIntent(NewIntent r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", r.orderId());
        body.put("amountMinor", r.amountMinor());
        body.put("currency", r.currency());
        body.put("placedAt", r.placedAt() == null ? null : r.placedAt().toString());
        body.put("metadata", r.metadata());
        if (r.shopperId() != null && !r.shopperId().isBlank()) {
            body.put("shopperId", r.shopperId());
        }
        body.put("lines", r.lines().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineId", l.lineId());
            m.put("sellerOrg", l.sellerOrg());
            m.put("category", l.category());
            m.put("fulfilment", l.fulfilment());
            m.put("quantity", l.quantity());
            m.put("itemsMinor", l.itemsMinor());
            m.put("shippingMinor", l.shippingMinor());
            m.put("taxMinor", l.taxMinor());
            m.put("taxJurisdiction", l.taxJurisdiction());
            m.put("shippingTaxMinor", l.shippingTaxMinor());
            if (l.deliveryFeeMinor() > 0) {
                m.put("deliveryFeeMinor", l.deliveryFeeMinor());
            }
            return m;
        }).toList());
        return intent(payments.call(c -> c.post().uri("/v1/payments")
                .headers(h -> {
                    h.setBearerAuth(tokens.token());
                    h.set(IDEMPOTENCY_KEY, r.orderId());
                })
                .body(body)
                .retrieve().body(JsonNode.class)));
    }

    @Override
    public DeliveryQuote quoteDelivery(String sellerOrgId, double distanceMiles, int packages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sellerOrgId", sellerOrgId);
        body.put("distanceMiles", distanceMiles);
        body.put("packages", packages);
        JsonNode q = payments.call(c -> c.post().uri("/v1/delivery-pricing/quote")
                .headers(h -> h.setBearerAuth(tokens.token()))
                .body(body)
                .retrieve().body(JsonNode.class));
        if (q == null) {
            throw new UpstreamFailure("payments", 0, "payments.invalid-response", "Empty delivery quote", true,
                    List.of(), null);
        }
        return new DeliveryQuote(q.path("available").asBoolean(false), q.path("unavailableReason").asString(null),
                q.path("customerChargeMinor").asLong(), q.path("sellerFeeMinor").asLong(),
                q.path("scheduleVersion").asInt());
    }

    @Override
    public Intent get(String paymentId, boolean refresh) {
        return intent(payments.call(c -> c.get().uri(u -> u.path("/v1/payments/{id}")
                        .queryParam("refresh", refresh).build(paymentId))
                .headers(h -> h.setBearerAuth(tokens.token()))
                .retrieve().body(JsonNode.class)));
    }

    @Override
    public Optional<Intent> findByOrder(String orderId) {
        try {
            return Optional.of(intent(payments.call(c -> c.get().uri(u -> u.path("/v1/payments")
                            .queryParam("orderId", orderId).build())
                    .headers(h -> h.setBearerAuth(tokens.token()))
                    .retrieve().body(JsonNode.class))));
        } catch (UpstreamFailure f) {
            if (f.status() == 404) {
                return Optional.empty();
            }
            throw f;
        }
    }

    @Override
    public void cancel(String paymentId, String reason) {
        payments.call(c -> c.post().uri("/v1/payments/{id}/cancel", paymentId)
                .headers(h -> {
                    h.setBearerAuth(tokens.token());
                    h.set(IDEMPOTENCY_KEY, "cancel-" + paymentId);
                })
                .body(Map.of("reason", reason == null ? "" : truncate(reason)))
                .retrieve().toBodilessEntity());
    }

    @Override
    public Capture capture(String paymentId, CaptureRequest r) {
        JsonNode body = payments.call(c -> c.post().uri("/v1/payments/{id}/capture", paymentId)
                .headers(h -> {
                    h.setBearerAuth(tokens.token());
                    h.set(IDEMPOTENCY_KEY, "capture-" + r.shipmentId());
                })
                .body(Map.of("shipmentId", r.shipmentId(), "lineIds", r.lineIds(), "amountMinor", r.amountMinor(),
                        "finalCapture", r.finalCapture()))
                .retrieve().body(JsonNode.class));
        return new Capture(body == null ? null : body.path("id").asString(null),
                body == null ? r.amountMinor() : body.path("amountMinor").asLong(r.amountMinor()));
    }

    @Override
    public String refund(String paymentId, String idempotencyKey, List<RefundLine> lines, String reason) {
        JsonNode body = payments.call(c -> c.post().uri("/v1/payments/{id}/refunds", paymentId)
                .headers(h -> {
                    h.setBearerAuth(tokens.token());
                    h.set(IDEMPOTENCY_KEY, idempotencyKey);
                })
                .body(Map.of("reason", truncate(reason), "lines", lines.stream().map(l -> Map.of("lineId", l.lineId(),
                        "itemsMinor", l.itemsMinor(), "shippingMinor", l.shippingMinor(), "taxMinor", l.taxMinor()))
                        .toList()))
                .retrieve().body(JsonNode.class));
        return body == null ? null : body.path("id").asString(null);
    }

    @Override
    public void delivered(String orderId, Instant deliveredAt) {
        payments.call(c -> c.post().uri("/v1/settlements/{orderId}/delivered", orderId)
                .headers(h -> h.setBearerAuth(tokens.token()))
                .body(Map.of("deliveredAt", deliveredAt.toString(), "kind", "PRODUCT"))
                .retrieve().toBodilessEntity());
    }

    @Override
    public void shipmentDelivered(String orderId, String shipmentId, Instant deliveredAt) {
        payments.call(c -> c.post().uri("/v1/settlements/{orderId}/shipments/{shipmentId}/delivered", orderId,
                        shipmentId)
                .headers(h -> h.setBearerAuth(tokens.token()))
                .body(Map.of("deliveredAt", deliveredAt.toString(), "kind", "PRODUCT"))
                .retrieve().toBodilessEntity());
    }

    @Override
    public Split split(String orderId) {
        JsonNode s = payments.call(c -> c.get().uri("/v1/orders/{orderId}/split", orderId)
                .headers(h -> h.setBearerAuth(tokens.token()))
                .retrieve().body(JsonNode.class));
        if (s == null) {
            return new Split(orderId, 0, 0, 0, null, List.of());
        }
        List<Recipient> recipients = new ArrayList<>();
        for (JsonNode r : s.path("recipients")) {
            recipients.add(new Recipient(r.path("recipient").asString(), r.path("kind").asString(null),
                    r.path("itemsMinor").asLong(), r.path("shippingMinor").asLong(), r.path("taxMinor").asLong(),
                    r.path("commissionMinor").asLong(), r.path("fbaFeesMinor").asLong(),
                    r.path("deliveryFeesMinor").asLong(), r.path("stripeFeeMinor").asLong(),
                    r.path("netMinor").asLong()));
        }
        return new Split(orderId, s.path("chargeMinor").asLong(), s.path("stripeFeeMinor").asLong(),
                s.path("taxCollectedMinor").asLong(), s.path("currency").asString(null), recipients);
    }

    private static Intent intent(JsonNode p) {
        if (p == null) {
            throw new UpstreamFailure("payments", 0, "payments.invalid-response", "payments returned no body", true,
                    null, null);
        }
        return new Intent(p.path("id").asString(), p.path("status").asString(null),
                p.path("clientSecret").asString(null), p.path("amountMinor").asLong(),
                p.path("failureMessage").asString(null));
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 500 ? s.substring(0, 500) : s;
    }
}
