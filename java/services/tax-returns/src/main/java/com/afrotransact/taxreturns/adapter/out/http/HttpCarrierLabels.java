package com.afrotransact.taxreturns.adapter.out.http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import com.afrotransact.taxreturns.domain.returns.ReturnLabel;
import com.afrotransact.taxreturns.port.CarrierLabels;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * carrier-gateway {@code POST /v1/return-labels} (shipping:label): a return label from the customer to the seller or
 * return centre, reference = RMA number so {@code carrier.tracking.updated} events find the RMA. The rate choice
 * (CHEAPEST / FASTEST, or SERVICE with the configured carrier and service) and box-free QR drop-off (PREFERRED: where a
 * carrier integration offers it, else a printed label) come from {@code at.taxreturns.returns.label}.
 */
@Component
class HttpCarrierLabels implements CarrierLabels {

    private final Upstream carrier;
    private final TaxReturnsProperties.Label label;

    HttpCarrierLabels(RestClient.Builder builder, TaxReturnsProperties props, ClientCredentialsTokens tokens,
                      JsonMapper json) {
        this.carrier = new Upstream("carrier-gateway", builder, props.upstreams().carrierUrl(),
                props.upstreams().timeout(), tokens, json);
        this.label = props.returns().label();
    }

    @Override
    public ReturnLabel buyReturnLabel(LabelRequest r, String idempotencyKey) {
        Map<String, Object> shipment = new LinkedHashMap<>();
        shipment.put("reference", r.reference());
        shipment.put("originNode", r.originNode());
        shipment.put("from", address(r.from()));
        shipment.put("to", address(r.to()));
        shipment.put("parcels", List.of(Map.of("weightLb", r.weightLb())));
        Map<String, Object> body = new LinkedHashMap<>();
        String choice = label.rateChoice().toUpperCase(Locale.ROOT);
        body.put("rateChoice", choice);
        if ("SERVICE".equals(choice)) {
            body.put("carrier", label.carrier());
            body.put("service", label.service());
        }
        if (!label.carriers().isEmpty()) {
            body.put("carriers", label.carriers());
        }
        if ("PREFERRED".equalsIgnoreCase(label.boxFree())) {
            body.put("mode", "BOX_FREE_QR");
            body.put("fallbackToLabel", true);
        }
        body.put("shipment", shipment);
        JsonNode n = carrier.call(c -> c.post().uri("/v1/return-labels")
                .headers(h -> carrier.headers(h, idempotencyKey)).body(body).retrieve().body(JsonNode.class));
        String qr = n.path("qrPayload").asString("");
        return new ReturnLabel(n.path("id").asString(null), n.path("carrier").asString(label.carrier()),
                n.path("trackingNumber").asString(null), n.path("documentUrl").asString(null),
                qr.isBlank() ? null : qr, n.path("dropOffNetwork").asString(null));
    }

    private static Map<String, Object> address(ReturnAddress a) {
        Map<String, Object> m = new LinkedHashMap<>();
        put(m, "name", a.name());
        put(m, "phone", a.phone());
        put(m, "line1", a.line1());
        put(m, "line2", a.line2());
        put(m, "city", a.city());
        put(m, "state", a.state());
        put(m, "postalCode", a.postalCode());
        put(m, "country", a.country());
        return m;
    }

    private static void put(Map<String, Object> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v);
        }
    }
}
