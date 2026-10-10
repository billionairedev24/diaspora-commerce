package com.afrotransact.taxreturns.adapter.out.http;

import java.util.LinkedHashMap;
import java.util.Map;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import com.afrotransact.taxreturns.port.ReturnPickupScheduler;
import com.afrotransact.taxreturns.port.UpstreamException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** fulfilment-service (contracts/openapi/fulfilment.yaml) return pickups with the tax-returns-service token. */
@Component
class HttpReturnPickupScheduler implements ReturnPickupScheduler {

    private final Upstream fulfilment;

    HttpReturnPickupScheduler(RestClient.Builder builder, TaxReturnsProperties props, ClientCredentialsTokens tokens,
                              JsonMapper json) {
        this.fulfilment = new Upstream("fulfilment", builder, props.upstreams().fulfilmentUrl(),
                props.upstreams().timeout(), tokens, json);
    }

    @Override
    public String schedule(PickupRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rmaId", r.rmaId().toString());
        body.put("rmaNumber", r.rmaNumber());
        body.put("orderId", r.orderId().toString());
        body.put("orderNumber", r.orderNumber());
        body.put("shipmentId", r.shipmentId().toString());
        body.put("customerId", r.customerId());
        body.put("sellerOrgId", r.sellerOrgId());
        body.put("address", address(r.address()));
        put(body, "depotNodeId", r.depotNodeId());
        body.put("readyFrom", r.readyFrom() == null ? null : r.readyFrom().toString());
        body.put("collectBy", r.collectBy() == null ? null : r.collectBy().toString());
        body.put("units", Math.max(1, r.units()));
        JsonNode n = fulfilment.call(c -> c.post().uri("/v1/return-pickups").headers(h -> fulfilment.headers(h, null))
                .body(body).retrieve().body(JsonNode.class));
        String id = n.path("id").asString("");
        if (id.isEmpty()) {
            throw new UpstreamException("fulfilment", 0, null, "fulfilment returned no pickup id", null);
        }
        return id;
    }

    @Override
    public void cancel(String pickupId, String reason) {
        fulfilment.call(c -> c.post().uri("/v1/return-pickups/{id}/cancel", pickupId)
                .headers(h -> fulfilment.headers(h, null))
                .body(Map.of("reason", reason == null ? "Return cancelled" : reason))
                .retrieve().body(JsonNode.class));
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
