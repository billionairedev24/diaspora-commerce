package com.afrotransact.taxreturns.adapter.out.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.returns.RefundLineAmount;
import com.afrotransact.taxreturns.port.PaymentsGateway;
import com.afrotransact.taxreturns.port.UpstreamException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * payments-service (contracts/openapi/payments.yaml): {@code GET /v1/payments?orderId=} for the lines as charged and
 * {@code POST /v1/payments/{id}/refunds} (line-level, Idempotency-Key). Payments reverses the seller payable, tax and
 * commission per its fee policy and takes back transferred seller funds with a transfer reversal.
 */
@Component
class HttpPaymentsGateway implements PaymentsGateway {

    private final Upstream payments;

    HttpPaymentsGateway(RestClient.Builder builder, TaxReturnsProperties props, ClientCredentialsTokens tokens,
                        JsonMapper json) {
        this.payments = new Upstream("payments", builder, props.upstreams().paymentsUrl(), props.upstreams().timeout(),
                tokens, json);
    }

    @Override
    public Optional<ChargedPayment> paymentForOrder(UUID orderId) {
        JsonNode n;
        try {
            n = payments.call(c -> c.get().uri("/v1/payments?orderId={id}", orderId)
                    .headers(h -> payments.headers(h, null)).retrieve().body(JsonNode.class));
        } catch (UpstreamException ex) {
            if (ex.status() == 404) {
                return Optional.empty();
            }
            throw ex;
        }
        String id = n.path("id").asString("");
        if (id.isEmpty()) {
            return Optional.empty();
        }
        List<ChargedLine> lines = new ArrayList<>();
        for (JsonNode l : n.path("lines")) {
            lines.add(new ChargedLine(l.path("lineId").asString(), l.path("sellerOrg").asString(null),
                    l.path("quantity").asInt(1), l.path("itemsMinor").asLong(), l.path("shippingMinor").asLong(),
                    l.path("taxMinor").asLong(), !l.path("captureId").asString("").isEmpty(),
                    l.path("refundedItemsMinor").asLong(0), l.path("refundedShippingMinor").asLong(0),
                    l.path("refundedTaxMinor").asLong(0)));
        }
        return Optional.of(new ChargedPayment(UUID.fromString(id), n.path("currency").asString("USD"), lines));
    }

    @Override
    public IssuedRefund refund(UUID paymentId, List<RefundLineAmount> lines, String reason, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", lines.stream().map(l -> Map.of("lineId", l.lineId(), "itemsMinor", l.itemsMinor(),
                "shippingMinor", l.shippingMinor(), "taxMinor", l.taxMinor())).toList());
        body.put("reason", reason == null ? "Return" : reason.length() > 500 ? reason.substring(0, 500) : reason);
        JsonNode n = payments.call(c -> c.post().uri("/v1/payments/{id}/refunds", paymentId)
                .headers(h -> payments.headers(h, idempotencyKey)).body(body).retrieve().body(JsonNode.class));
        return new IssuedRefund(n.path("id").asString(null), n.path("status").asString(null),
                n.path("amountMinor").asLong());
    }
}
