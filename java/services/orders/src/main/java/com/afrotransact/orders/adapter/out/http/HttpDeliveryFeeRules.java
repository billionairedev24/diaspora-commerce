package com.afrotransact.orders.adapter.out.http;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.orders.domain.FeeRuleSnapshot;
import com.afrotransact.orders.port.DeliveryFeeRules;
import com.afrotransact.orders.port.ServiceTokenProvider;
import com.afrotransact.orders.port.UpstreamFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * delivery-runs (port 8115) {@code POST /v1/service/delivery-fees/carrier} with the orders-service token
 * ({@code delivery-runs:reserve}). A checkout never fails because delivery-runs cannot answer: it falls back to its
 * own flat carrier rates (logged).
 */
@Component
class HttpDeliveryFeeRules implements DeliveryFeeRules {

    private static final Logger LOG = LoggerFactory.getLogger(HttpDeliveryFeeRules.class);

    private final Upstream deliveryRuns;
    private final ServiceTokenProvider tokens;

    HttpDeliveryFeeRules(RestClient.Builder builder,
                         @Value("${at.orders.upstreams.delivery-runs-url}") String baseUrl,
                         @Value("${at.orders.upstreams.timeout:5s}") Duration timeout, ServiceTokenProvider tokens) {
        this.deliveryRuns = new Upstream("delivery-runs", builder, baseUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public Optional<FeeRuleSnapshot> carrier(String currency, Long quoteMinor, long itemsMinor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currency", currency);
        body.put("itemsMinor", itemsMinor);
        if (quoteMinor != null) {
            body.put("carrierQuoteMinor", quoteMinor);
        }
        try {
            JsonNode answer = deliveryRuns.call(c -> c.post().uri("/v1/service/delivery-fees/carrier")
                    .headers(h -> h.setBearerAuth(tokens.token())).contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(JsonNode.class));
            if (answer == null || !answer.path("applies").asBoolean(false)) {
                return Optional.empty();
            }
            return Optional.ofNullable(snapshot(answer.path("fee")));
        } catch (UpstreamFailure f) {
            LOG.warn("delivery-runs carrier fee rule unavailable ({}); using the flat carrier rate", f.code());
            return Optional.empty();
        }
    }

    /** Reads a {@code DeliveryFeeRuleRef}; null when it is missing or incomplete. */
    static FeeRuleSnapshot snapshot(JsonNode ref) {
        if (ref == null || !ref.isObject() || ref.path("method").asString("").isBlank()
                || !ref.path("amountMinor").isNumber()) {
            return null;
        }
        JsonNode miles = ref.path("distanceMiles");
        return new FeeRuleSnapshot(ref.path("method").asString(), blankToNull(ref.path("ruleId").asString("")),
                ref.path("ruleVersion").asLong(0), ref.path("currency").asString("USD"),
                ref.path("amountMinor").asLong(), ref.path("source").asString("RULE"),
                blankToNull(ref.path("band").asString("")),
                miles.isNumber() ? new BigDecimal(miles.asString()) : null);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
