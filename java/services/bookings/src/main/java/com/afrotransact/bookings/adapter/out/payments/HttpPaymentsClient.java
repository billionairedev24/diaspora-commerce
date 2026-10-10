package com.afrotransact.bookings.adapter.out.payments;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.afrotransact.bookings.config.BookingsProperties;
import com.afrotransact.bookings.port.PaymentsClient;
import com.afrotransact.bookings.port.PaymentsClientException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link PaymentsClient} over payments-service's REST API (contracts/openapi/payments.yaml):
 * {@code POST /v1/payments}, {@code GET /v1/payments/{id}?refresh=}, {@code POST /v1/payments/{id}/capture},
 * {@code /cancel}, {@code /refunds} and {@code POST /v1/settlements/{orderId}/delivered} (kind SERVICE).
 */
@Component
class HttpPaymentsClient implements PaymentsClient {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RestClient client;
    private final ClientCredentialsTokens tokens;
    private final JsonMapper json;

    HttpPaymentsClient(RestClient.Builder builder, BookingsProperties props, JsonMapper json, Clock clock) {
        BookingsProperties.Payments cfg = props.payments();
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(cfg.timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(cfg.timeout());
        RestClient base = builder.clone().requestFactory(factory).build();
        this.client = base.mutate().baseUrl(cfg.baseUrl()).build();
        this.tokens = new ClientCredentialsTokens(base, cfg.tokenUri(), cfg.clientId(), cfg.clientSecret(), clock);
        this.json = json;
    }

    @Override
    public Intent createIntent(CreateIntent r, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", r.orderId());
        body.put("amountMinor", r.amountMinor());
        body.put("currency", r.currency());
        body.put("placedAt", r.placedAt());
        body.put("metadata", r.metadata());
        body.put("lines", r.lines().stream().map(l -> Map.<String, Object>of("lineId", l.lineId(),
                "sellerOrg", r.sellerOrg(), "category", "services", "fulfilment", "SELF", "quantity", 1,
                "itemsMinor", l.amountMinor(), "shippingMinor", 0, "taxMinor", 0)).toList());
        return call(c -> c.post().uri("/v1/payments").headers(h -> headers(h, idempotencyKey)).body(body)
                .retrieve().body(JsonNode.class), HttpPaymentsClient::intent);
    }

    @Override
    public Intent get(UUID paymentId, boolean refresh) {
        return call(c -> c.get().uri("/v1/payments/{id}?refresh={r}", paymentId, refresh)
                .headers(h -> headers(h, null)).retrieve().body(JsonNode.class), HttpPaymentsClient::intent);
    }

    @Override
    public void capture(UUID paymentId, String captureId, List<String> lineIds, long amountMinor,
                        boolean finalCapture, String idempotencyKey) {
        Map<String, Object> body = Map.of("shipmentId", captureId, "lineIds", lineIds, "amountMinor", amountMinor,
                "finalCapture", finalCapture);
        call(c -> c.post().uri("/v1/payments/{id}/capture", paymentId).headers(h -> headers(h, idempotencyKey))
                .body(body).retrieve().body(JsonNode.class), n -> n);
    }

    @Override
    public void cancel(UUID paymentId, String reason, String idempotencyKey) {
        Map<String, Object> body = Map.of("reason", reason == null ? "Booking cancelled" : truncate(reason));
        call(c -> c.post().uri("/v1/payments/{id}/cancel", paymentId).headers(h -> headers(h, idempotencyKey))
                .body(body).retrieve().body(JsonNode.class), n -> n);
    }

    @Override
    public void refund(UUID paymentId, String lineId, long amountMinor, String reason, String idempotencyKey) {
        Map<String, Object> body = Map.of("lines", List.of(Map.of("lineId", lineId, "itemsMinor", amountMinor)),
                "reason", reason == null ? "Booking cancelled" : truncate(reason));
        call(c -> c.post().uri("/v1/payments/{id}/refunds", paymentId).headers(h -> headers(h, idempotencyKey))
                .body(body).retrieve().body(JsonNode.class), n -> n);
    }

    @Override
    public void serviceCompleted(String orderId, Instant completedAt) {
        Map<String, Object> body = Map.of("deliveredAt", completedAt, "kind", "SERVICE");
        call(c -> c.post().uri("/v1/settlements/{orderId}/delivered", orderId).headers(h -> headers(h, null))
                .body(body).retrieve().body(JsonNode.class), n -> n);
    }

    private void headers(HttpHeaders h, String idempotencyKey) {
        if (tokens.enabled()) {
            h.setBearerAuth(tokens.token());
        }
        if (idempotencyKey != null) {
            h.set(IDEMPOTENCY_KEY, idempotencyKey);
        }
    }

    private <T> T call(Function<RestClient, JsonNode> request, Function<JsonNode, T> map) {
        try {
            JsonNode node = request.apply(client);
            return map.apply(node == null ? json.createObjectNode() : node);
        } catch (RestClientResponseException e) {
            String code = null;
            String detail = e.getStatusText();
            try {
                JsonNode problem = json.readTree(e.getResponseBodyAsString());
                code = problem.path("code").asString(null);
                detail = problem.path("detail").asString(detail);
            } catch (RuntimeException ignored) {
                // not a problem document
            }
            throw new PaymentsClientException(e.getStatusCode().value(), code, "Payments: " + detail, e);
        } catch (RestClientException | IllegalStateException e) {
            throw new PaymentsClientException(0, null, "Payments service unavailable: " + e.getMessage(), e);
        }
    }

    private static Intent intent(JsonNode n) {
        String id = n.path("id").asString("");
        return new Intent(id.isEmpty() ? null : UUID.fromString(id), n.path("orderId").asString(null),
                n.path("status").asString(null), n.path("clientSecret").asString(null),
                n.path("failureMessage").asString(null));
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
