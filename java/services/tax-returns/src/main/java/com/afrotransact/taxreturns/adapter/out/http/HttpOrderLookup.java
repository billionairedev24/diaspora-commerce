package com.afrotransact.taxreturns.adapter.out.http;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.returns.DeliveryMethod;
import com.afrotransact.taxreturns.domain.returns.FulfilmentType;
import com.afrotransact.taxreturns.domain.returns.PlacedOrder;
import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.port.OrderLookup;
import com.afrotransact.taxreturns.port.UpstreamException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** orders-service (contracts/openapi/orders.yaml) {@code GET /v1/lookup/orders/{id}} with the service token. */
@Component
class HttpOrderLookup implements OrderLookup {

    private final Upstream orders;

    HttpOrderLookup(RestClient.Builder builder, TaxReturnsProperties props, ClientCredentialsTokens tokens,
                    JsonMapper json) {
        this.orders = new Upstream("orders", builder, props.upstreams().ordersUrl(), props.upstreams().timeout(),
                tokens, json);
    }

    @Override
    public Optional<PlacedOrder> placedOrder(UUID orderId) {
        JsonNode n;
        try {
            n = orders.call(c -> c.get().uri("/v1/lookup/orders/{id}", orderId)
                    .headers(h -> orders.headers(h, null)).retrieve().body(JsonNode.class));
        } catch (UpstreamException ex) {
            if (ex.status() == 404) {
                return Optional.empty();
            }
            throw ex;
        }
        if (text(n, "orderId") == null || text(n, "placedAt") == null) {
            return Optional.empty();
        }
        JsonNode to = n.path("shipTo");
        List<PlacedOrder.Shipment> shipments = new ArrayList<>();
        for (JsonNode s : n.path("shipments")) {
            if ("cancelled".equals(text(s, "status"))) {
                continue;
            }
            List<PlacedOrder.Line> lines = new ArrayList<>();
            for (JsonNode l : s.path("lines")) {
                lines.add(new PlacedOrder.Line(text(l, "lineId"), text(l, "variantAtin"), text(l, "title"),
                        l.path("quantity").asInt(1), l.path("unitPriceMinor").asLong(), l.path("taxMinor").asLong(),
                        text(l, "productType"), l.path("perishable").asBoolean(false), l.path("discountMinor").asLong()));
            }
            shipments.add(new PlacedOrder.Shipment(UUID.fromString(text(s, "shipmentId")), text(s, "sellerOrgId"),
                    text(s, "sellerName"), enumOr(text(s, "fulfilmentType"), FulfilmentType.SELLER_FULFILLED),
                    enumOr(text(s, "deliveryMethod"), DeliveryMethod.CARRIER), s.path("totalMinor").asLong(),
                    s.path("shippingMinor").asLong(), text(s, "deliveredAt") == null ? null
                    : Instant.parse(text(s, "deliveredAt")), lines));
        }
        String country = text(to, "country");
        ReturnAddress customer = text(to, "line1") == null ? null : new ReturnAddress(text(to, "fullName"),
                text(to, "line1"), text(to, "line2"), text(to, "city"), text(to, "state"), text(to, "postalCode"),
                country, text(to, "phone"));
        String currency = text(n, "currency");
        return Optional.of(new PlacedOrder(UUID.fromString(text(n, "orderId")), text(n, "orderNumber"),
                text(n, "customerId"), currency == null ? "USD" : currency.toUpperCase(Locale.ROOT),
                new TaxAddress(country, text(to, "state"), text(to, "postalCode"), text(to, "city")),
                Instant.parse(text(n, "placedAt")), shipments, customer));
    }

    private static <E extends Enum<E>> E enumOr(String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString(null);
    }
}
