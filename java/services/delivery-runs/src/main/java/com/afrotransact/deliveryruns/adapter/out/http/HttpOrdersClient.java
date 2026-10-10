package com.afrotransact.deliveryruns.adapter.out.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.deliveryruns.domain.PreorderLine;
import com.afrotransact.deliveryruns.port.OrdersGateway;
import com.afrotransact.deliveryruns.port.OrdersGateway.Address;
import com.afrotransact.deliveryruns.port.UpstreamRejectedException;
import com.afrotransact.deliveryruns.port.UpstreamUnavailableException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * orders-service {@code POST /v1/service/orders/late-preorder} with the {@code delivery-runs-service} token (scope
 * orders:on-behalf) and an {@code Idempotency-Key}. 4xx problems are passed on with their code.
 */
public class HttpOrdersClient implements OrdersGateway {

    private static final String SERVICE = "orders";

    private final RestClient orders;
    private final ClientCredentialsTokens tokens;
    private final JsonMapper json;

    public HttpOrdersClient(String baseUrl, Duration timeout, String tokenUri, String clientId, String clientSecret,
                            Clock clock, JsonMapper json) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.orders = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = new ClientCredentialsTokens(tokenUri, clientId, clientSecret, "orders:on-behalf", timeout, clock,
                (message, cause) -> new UpstreamUnavailableException(SERVICE, message, cause));
        this.json = json;
    }

    @Override
    public LatePreorderResult placeLatePreorder(LatePreorderRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", r.customerId());
        body.put("windowId", r.windowId().toString());
        Address a = r.address();
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("addressId", a.addressId());
        address.put("fullName", a.fullName());
        address.put("phone", a.phone());
        address.put("line1", a.line1());
        address.put("line2", a.line2());
        address.put("city", a.city());
        address.put("state", a.state());
        address.put("postalCode", a.postalCode());
        address.put("country", a.country());
        address.put("instructions", a.instructions());
        body.put("address", address);
        body.put("items", r.items());
        body.put("fulfilment", r.fulfilment());
        body.put("payment", r.payment().name());
        body.put("reason", r.reason());
        body.put("note", r.note());
        body.put("actorId", r.actorId());
        body.put("phoneConfirmed", r.phoneConfirmed());
        body.put("lateAdd", Map.of("savedCard", r.lateAdd().savedCard(), "paymentLink", r.lateAdd().paymentLink(),
                "linkExpiryHours", r.lateAdd().linkExpiryHours()));
        JsonNode res;
        try {
            res = orders.post().uri("/v1/service/orders/late-preorder")
                    .header("Authorization", "Bearer " + tokens.token())
                    .header("Idempotency-Key", r.idempotencyKey())
                    .body(body).retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException e) {
            throw rejected(e);
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException(SERVICE, "orders-service failed: " + e.getMessage(), e);
        }
        if (res == null || text(res, "orderId") == null) {
            throw new UpstreamUnavailableException(SERVICE, "orders-service returned no order", null);
        }
        JsonNode to = res.path("shipTo");
        List<PreorderLine> lines = new ArrayList<>();
        for (JsonNode l : res.path("lines")) {
            lines.add(new PreorderLine(first(l, "atin", "variantAtin"), first(l, "name", "title"),
                    text(l, "sellerOrgId"), text(l, "sellerName"),
                    l.path("qty").asInt(l.path("quantity").asInt(1))));
        }
        String shipmentId = text(res, "shipmentId");
        if (shipmentId == null && res.path("shipmentIds").isArray() && !res.path("shipmentIds").isEmpty()) {
            shipmentId = res.path("shipmentIds").get(0).asString();
        }
        return new LatePreorderResult(text(res, "orderId"), first(res, "orderNumber", "number"), text(res, "status"),
                res.path("totalMinor").asLong(0), text(res, "currency"), text(res, "paymentLinkUrl"),
                instant(text(res, "paymentLinkExpiresAt")), shipmentId, first(to, "name", "recipientName"),
                first(to, "phone", "recipientPhone"),
                text(res, "customerEmail"), first(to, "line1", "addressLine1"), text(to, "city"),
                first(to, "region", "state"), first(to, "zip", "postalCode"), lines);
    }

    private UpstreamRejectedException rejected(HttpClientErrorException e) {
        String code = "orders.rejected";
        String detail = "orders-service refused the late order (" + e.getStatusCode().value() + ")";
        try {
            JsonNode p = json.readTree(e.getResponseBodyAsString());
            code = p.path("code").asString(code);
            detail = p.path("detail").asString(detail);
        } catch (JacksonException | IllegalArgumentException ignored) {
            // not a problem document: keep the generic message
        }
        return new UpstreamRejectedException(code, detail, e.getStatusCode().value());
    }

    private static Instant instant(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String first(JsonNode n, String a, String b) {
        String v = text(n, a);
        return v != null ? v : text(n, b);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }
}
