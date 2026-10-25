package com.afrotransact.fulfilment.adapter.out.http;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.fulfilment.domain.GeoPoint;
import com.afrotransact.fulfilment.domain.RunPickupPoint;
import com.afrotransact.fulfilment.port.DeliveryRunsClient;
import com.afrotransact.fulfilment.port.ServiceTokenProvider;
import com.afrotransact.fulfilment.port.UpstreamFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** delivery-runs-service (port 8115): {@code GET /v1/service/runs/{runId}/dispatch} with the service token. */
@Component
class HttpDeliveryRunsClient implements DeliveryRunsClient {

    private final Upstream runs;
    private final ServiceTokenProvider tokens;

    HttpDeliveryRunsClient(RestClient.Builder builder,
                           @Value("${at.fulfilment.upstreams.delivery-runs-url}") String baseUrl,
                           @Value("${at.fulfilment.upstreams.timeout:5s}") Duration timeout,
                           ServiceTokenProvider tokens) {
        this.runs = new Upstream("delivery-runs", builder, baseUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public RunPlan dispatch(String runId) {
        JsonNode d = runs.call(c -> c.get().uri("/v1/service/runs/{id}/dispatch", runId)
                .headers(h -> h.setBearerAuth(tokens.token())).retrieve().body(JsonNode.class));
        if (d == null || d.path("run").path("id").asString("").isEmpty()) {
            throw new UpstreamFailure("delivery-runs", 0, "delivery-runs.invalid-response",
                    "delivery-runs sent no run for " + runId, true, null);
        }
        JsonNode run = d.path("run");
        JsonNode loc = d.path("location");
        List<String> orders = new ArrayList<>();
        for (JsonNode o : d.path("orders")) {
            String status = o.path("status").asString("CONFIRMED");
            if ("CONFIRMED".equals(status) && !o.path("orderId").asString("").isEmpty()) {
                orders.add(o.path("orderId").asString());
            }
        }
        JsonNode p = d.path("pickupPoint");
        RunPickupPoint point = p.isObject() && !p.path("name").asString("").isEmpty()
                ? new RunPickupPoint(p.path("name").asString(), text(p, "line1"), text(p, "city"), text(p, "state"),
                text(p, "postalCode"), text(p, "hours"), text(p, "notes")) : null;
        GeoPoint origin = loc.path("originLat").isNumber() && loc.path("originLng").isNumber()
                ? new GeoPoint(loc.path("originLat").asDouble(), loc.path("originLng").asDouble()) : null;
        boolean doorstep = !"PICKUP_POINT".equals(d.path("dropMode").asString("DOORSTEP"));
        return new RunPlan(run.path("id").asString(), run.path("status").asString(), text(loc, "name"),
                text(loc, "state"), text(loc, "timeZone") == null ? "America/Chicago" : text(loc, "timeZone"),
                instant(run, "windowStart"), instant(run, "windowEnd"), instant(d, "sellerPickupAt"), doorstep, point,
                origin, orders);
    }

    private static String text(JsonNode n, String field) {
        String v = n.path(field).asString("");
        return v.isBlank() ? null : v;
    }

    private static Instant instant(JsonNode n, String field) {
        String v = text(n, field);
        return v == null ? null : Instant.parse(v);
    }
}
