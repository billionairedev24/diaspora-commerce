package com.afrotransact.orders.adapter.out.http;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.afrotransact.orders.domain.PreorderStage;
import com.afrotransact.orders.port.PreorderEligibility;
import com.afrotransact.orders.port.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * delivery-runs (port 8115) {@code GET /v1/service/preorders/eligibility} with the orders-service token
 * ({@code delivery-runs:reserve}). Reads the answer tolerantly: a list (bare, or under {@code items}/{@code lines}/
 * {@code results}) or an object keyed by ATIN; window fields flat or under {@code window}; fees as
 * {@code doorFee} / {@code doorFeeMinor} in minor units (799 = $7.99; a value with a fraction is read as given units)
 * or {@code {amountMinor}}. Fields delivery-runs does not send default to door 799, pickup 0, USD and every payment
 * method.
 */
@Component
class HttpPreorderEligibility implements PreorderEligibility {

    private final Upstream deliveryRuns;
    private final ServiceTokenProvider tokens;

    HttpPreorderEligibility(RestClient.Builder builder,
                            @Value("${at.orders.upstreams.delivery-runs-url}") String baseUrl,
                            @Value("${at.orders.upstreams.timeout:5s}") Duration timeout,
                            ServiceTokenProvider tokens) {
        this.deliveryRuns = new Upstream("delivery-runs", builder, baseUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public Map<String, Line> eligibility(String country, String region, String city,
                                         Collection<String> variantAtins) {
        Map<String, Line> out = new LinkedHashMap<>();
        if (variantAtins.isEmpty()) {
            return out;
        }
        JsonNode body = deliveryRuns.call(c -> c.get().uri(u -> {
            u.path("/v1/service/preorders/eligibility").queryParam("country", blank(country) ? "US" : country)
                    .queryParam("atins", String.join(",", variantAtins));
            if (!blank(region)) {
                u.queryParam("region", region);
            }
            if (!blank(city)) {
                u.queryParam("city", city);
            }
            return u.build();
        }).headers(h -> h.setBearerAuth(tokens.token())).retrieve().body(JsonNode.class));
        if (body == null) {
            return out;
        }
        JsonNode list = body.isArray() ? body : first(body, "items", "lines", "results", "atins", "products");
        if (list != null && list.isArray()) {
            for (JsonNode n : list) {
                Line l = line(n, text(n, "atin", text(n, "variantAtin", null)));
                if (l != null) {
                    out.put(l.variantAtin(), l);
                }
            }
        } else {
            JsonNode map = list != null && list.isObject() ? list : body;
            for (String atin : variantAtins) {
                JsonNode n = map.get(atin);
                if (n != null && n.isObject()) {
                    Line l = line(n, atin);
                    if (l != null) {
                        out.put(atin, l);
                    }
                }
            }
        }
        return out;
    }

    private static Line line(JsonNode n, String atin) {
        if (atin == null) {
            return null;
        }
        JsonNode w = n.path("window").isObject() ? n.path("window") : n;
        String windowId = text(n, "windowId", text(w, "id", text(w, "windowId", null)));
        boolean preorder = n.path("preorder").asBoolean(false) && windowId != null;
        LocalDate deliversOn = date(text(n, "deliversOn", text(w, "deliversOn", null)));
        if (preorder && deliversOn == null) {
            preorder = false;
        }
        JsonNode fees = n.path("fees").isObject() ? n.path("fees") : w.path("fees").isObject() ? w.path("fees") : w;
        Set<String> methods = new LinkedHashSet<>();
        JsonNode allowed = n.has("allowedMethods") ? n.path("allowedMethods") : w.path("allowedMethods");
        for (JsonNode m : allowed) {
            String v = m.asString("").trim().toUpperCase(Locale.ROOT);
            if (!v.isEmpty()) {
                methods.add(v);
            }
        }
        return new Line(atin, preorder, windowId, text(n, "windowCode", text(w, "code", null)),
                text(n, "windowName", text(w, "name", null)),
                PreorderStage.parse(text(n, "stage", text(w, "stage", text(w, "status", null)))), deliversOn,
                instant(text(n, "closesAt", text(w, "closesAt", null))), bool(n, w, "door", true),
                bool(n, w, "pickup", false), fee(n, fees, "door", DEFAULT_DOOR_FEE_MINOR),
                fee(n, fees, "pickup", 0), text(n, "currency", text(w, "currency", text(fees, "currency", "USD"))),
                methods, text(n, "pickupPointId", text(w, "pickupPointId", null)),
                HttpDeliveryFeeRules.snapshot(rule(n, w, "doorFeeRule")),
                HttpDeliveryFeeRules.snapshot(rule(n, w, "pickupFeeRule")));
    }

    /** {@code <kind>FeeMinor}, else {@code <kind>Fee} in minor units (or an object with amountMinor / minor). */
    private static long fee(JsonNode n, JsonNode fees, String kind, long fallback) {
        for (JsonNode src : new JsonNode[] {n, fees}) {
            JsonNode minor = src.path(kind + "FeeMinor");
            if (minor.isNumber()) {
                return minor.asLong();
            }
            JsonNode given = src.path(kind + "Fee");
            if (given.isObject()) {
                JsonNode m = given.has("amountMinor") ? given.path("amountMinor") : given.path("minor");
                if (m.isNumber()) {
                    return m.asLong();
                }
            } else if (given.isNumber() || given.isString() && !given.asString("").isBlank()) {
                try {
                    BigDecimal v = new BigDecimal(given.asString());
                    BigDecimal minorUnits = v.stripTrailingZeros().scale() > 0 ? v.movePointRight(2) : v;
                    return minorUnits.setScale(0, RoundingMode.HALF_UP).longValueExact();
                } catch (NumberFormatException | ArithmeticException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static JsonNode rule(JsonNode n, JsonNode w, String field) {
        return n.has(field) ? n.path(field) : w.path(field);
    }

    private static boolean bool(JsonNode n, JsonNode w, String field, boolean fallback) {
        JsonNode v = n.has(field) ? n.path(field) : w.path(field);
        return v.isBoolean() ? v.asBoolean() : fallback;
    }

    private static JsonNode first(JsonNode body, String... fields) {
        for (String f : fields) {
            if (body.has(f)) {
                return body.get(f);
            }
        }
        return null;
    }

    private static String text(JsonNode n, String field, String fallback) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asString("").isBlank() ? fallback : v.asString();
    }

    private static LocalDate date(String v) {
        if (v == null) {
            return null;
        }
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Instant instant(String v) {
        if (v == null) {
            return null;
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
