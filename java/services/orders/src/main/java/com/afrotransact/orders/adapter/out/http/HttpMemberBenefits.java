package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.orders.port.MemberBenefits;
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
 * payments-service (port 8105) {@code POST /v1/memberships/waivers}. A checkout never fails because payments cannot
 * answer: the customer simply pays the fee (logged).
 */
@Component
class HttpMemberBenefits implements MemberBenefits {

    private static final Logger LOG = LoggerFactory.getLogger(HttpMemberBenefits.class);

    private final Upstream payments;
    private final ServiceTokenProvider tokens;

    HttpMemberBenefits(RestClient.Builder builder, @Value("${at.orders.upstreams.payments-url}") String baseUrl,
                       @Value("${at.orders.upstreams.timeout:5s}") Duration timeout, ServiceTokenProvider tokens) {
        this.payments = new Upstream("payments", builder, baseUrl, timeout);
        this.tokens = tokens;
    }

    @Override
    public int earlyAccessHours(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return 0;
        }
        try {
            JsonNode answer = payments.call(c -> c.get().uri("/v1/memberships/customers/{id}/entitlements",
                    customerId).headers(h -> h.setBearerAuth(tokens.token())).retrieve().body(JsonNode.class));
            return answer == null || !answer.path("member").asBoolean(false) ? 0
                    : Math.max(0, answer.path("earlyAccessHours").asInt(0));
        } catch (UpstreamFailure f) {
            LOG.warn("Membership entitlements unavailable ({}); no early access for this order", f.code());
            return 0;
        }
    }

    @Override
    public Optional<Waivers> waivers(String customerId, long itemsMinor, String currency, List<Fee> fees) {
        if (customerId == null || customerId.isBlank() || fees.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("itemsMinor", itemsMinor);
        body.put("currency", currency);
        body.put("fees", fees.stream().map(f -> Map.of("key", f.key(), "kind", f.kind().name(), "feeMinor",
                f.feeMinor())).toList());
        try {
            JsonNode answer = payments.call(c -> c.post().uri("/v1/memberships/waivers")
                    .headers(h -> h.setBearerAuth(tokens.token())).contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(JsonNode.class));
            if (answer == null || !answer.path("member").asBoolean(false)) {
                return Optional.empty();
            }
            List<Waiver> waived = new ArrayList<>();
            for (JsonNode w : answer.path("waivers")) {
                long amount = w.path("waivedMinor").asLong(0);
                if (amount > 0) {
                    waived.add(new Waiver(w.path("key").asString(), w.path("benefit").asString(), amount));
                }
            }
            return waived.isEmpty() ? Optional.empty() : Optional.of(new Waivers(answer.path("tierCode").asString(),
                    answer.path("tierName").asString(answer.path("tierCode").asString()), waived));
        } catch (UpstreamFailure f) {
            LOG.warn("Membership benefits unavailable ({}); the customer pays the delivery fees", f.code());
            return Optional.empty();
        }
    }
}
