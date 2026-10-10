package com.afrotransact.trustops.adapter.out.http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.trustops.config.TrustOpsProperties;
import com.afrotransact.trustops.port.PaymentsClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** payments-service {@code POST /v1/payments/{id}/refunds} (RefundRequest → RefundResponse). */
@Component
class HttpPaymentsClient implements PaymentsClient {

    private static final String SERVICE = "payments";

    private final Downstream downstream;
    private final RestClient client;

    HttpPaymentsClient(Downstream downstream, TrustOpsProperties props) {
        this.downstream = downstream;
        this.client = downstream.client(props.payments().baseUrl(), null);
    }

    @Override
    public RefundResult refund(UUID paymentId, List<RefundLine> lines, String reason, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", lines.stream().map(l -> Map.<String, Object>of("lineId", l.lineId(),
                "itemsMinor", l.itemsMinor(), "shippingMinor", l.shippingMinor(), "taxMinor", l.taxMinor())).toList());
        body.put("reason", reason);
        return downstream.call(SERVICE, client, c -> c.post().uri("/v1/payments/{id}/refunds", paymentId)
                .headers(h -> downstream.headers(h, idempotencyKey)).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class),
                n -> new RefundResult(n.path("id").asString(null), n.path("amountMinor").asLong(0),
                        n.path("status").asString(null), n.path("replayed").asBoolean(false)));
    }
}
