package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.afrotransact.orders.port.DeliveryRunSlots;
import com.afrotransact.orders.port.ServiceTokenProvider;
import com.afrotransact.orders.port.UpstreamFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * delivery-runs (port 8115): {@code GET /v1/public/runs/available?zip=} (public) and the slot reservation API with the
 * orders-service token ({@code delivery-runs:reserve}). A reservation is asked for exactly this run
 * ({@code allowNextRun=false}): checkout quoted its date and fee. 409 {@code delivery-runs.run-closed} /
 * {@code run-full} become {@link RunUnavailable}.
 */
@Component
class HttpDeliveryRunSlots implements DeliveryRunSlots {

    private final Upstream deliveryRuns;
    private final ServiceTokenProvider tokens;

    HttpDeliveryRunSlots(RestClient.Builder builder,
                         @Value("${at.orders.upstreams.delivery-runs-url}") String baseUrl,
                         @Value("${at.orders.upstreams.timeout:5s}") Duration timeout, ServiceTokenProvider tokens) {
        this.deliveryRuns = new Upstream("delivery-runs", builder, baseUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public List<OpenRun> openRuns(String zip) {
        JsonNode body = deliveryRuns.call(c -> c.get().uri(u -> u.path("/v1/public/runs/available")
                .queryParam("zip", zip).build()).retrieve().body(JsonNode.class));
        List<OpenRun> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        for (JsonNode r : body.path("runs")) {
            OpenRun run = run(r);
            if (run != null) {
                out.add(run);
            }
        }
        return out;
    }

    @Override
    public void reserve(String runId, String orderId, String customerId) {
        try {
            deliveryRuns.call(c -> c.post().uri("/v1/service/runs/{id}/reservations", runId)
                    .headers(h -> h.setBearerAuth(tokens.token())).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("orderId", orderId, "customerId", customerId, "packages", 1,
                            "allowNextRun", false))
                    .retrieve().toBodilessEntity());
        } catch (UpstreamFailure f) {
            if (f.status() == 409 && "delivery-runs.run-full".equals(f.code())) {
                throw new RunUnavailable("orders.run-full", "This delivery run is full");
            }
            if (f.status() == 409 && "delivery-runs.run-closed".equals(f.code())) {
                throw new RunUnavailable("orders.run-closed", "This delivery run has closed for orders");
            }
            throw f;
        }
    }

    @Override
    public void release(String runId, String orderId) {
        try {
            deliveryRuns.call(c -> c.delete().uri("/v1/service/runs/{id}/reservations/{order}", runId, orderId)
                    .headers(h -> h.setBearerAuth(tokens.token())).retrieve().toBodilessEntity());
        } catch (UpstreamFailure f) {
            if (f.status() != 404) {
                throw f;
            }
        }
    }

    private static OpenRun run(JsonNode r) {
        String id = text(r, "runId");
        Instant cutoff = instant(text(r, "cutoffAt"));
        LocalDate day = date(text(r, "deliversOn"));
        if (id == null || cutoff == null || day == null || !r.path("acceptingOrders").asBoolean(true)) {
            return null;
        }
        boolean pickup = "PICKUP_POINT".equalsIgnoreCase(text(r, "dropMode"));
        return new OpenRun(id, text(r, "town"), cutoff, day, text(r, "timeZone"), r.path("feeMinor").asLong(0),
                text(r, "currency") == null ? "USD" : text(r, "currency"), !pickup, pickup, null,
                HttpDeliveryFeeRules.snapshot(r.path("feeRule")), r.path("minOrders").asInt(0),
                r.path("ordersSoFar").asInt(0), r.path("full").asBoolean(false));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asString("").isBlank() ? null : v.asString();
    }

    private static LocalDate date(String v) {
        try {
            return v == null ? null : LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant instant(String v) {
        try {
            return v == null ? null : Instant.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
