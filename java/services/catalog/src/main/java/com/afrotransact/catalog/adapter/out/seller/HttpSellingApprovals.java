package com.afrotransact.catalog.adapter.out.seller;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.catalog.port.SellingApprovals;
import com.afrotransact.catalog.port.ServiceTokens;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * seller-service {@code GET /v1/orgs/{orgId}/selling-status} with catalog's service token (scope
 * {@code sellers:catalog-scope.read}), cached for a few seconds per organisation and listing facts (an approval granted
 * a moment ago shows within the TTL). A failure is never cached and never guessed: it is
 * {@link SellingUnavailable}, so the catalog refuses (fail closed).
 */
@Component
class HttpSellingApprovals implements SellingApprovals {

    private final RestClient seller;
    private final ServiceTokens tokens;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    HttpSellingApprovals(RestClient.Builder builder, ServiceTokens tokens,
                         @Value("${at.catalog.seller.base-url:http://localhost:8106}") String baseUrl,
                         @Value("${at.catalog.seller.timeout:3s}") Duration timeout,
                         @Value("${at.catalog.seller.selling-ttl:10s}") Duration ttl, Clock clock) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.seller = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = tokens;
        this.ttl = ttl;
        this.clock = clock;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ScopeJson(String type, String key) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MissingJson(ScopeJson scope, String label, String level, String state, String message, boolean canApply) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StatusJson(String level, String reason, List<MissingJson> missing, List<String> pendingFacts) {
    }

    private record Cached(Status status, Instant until) {
    }

    @Override
    public Status statusOf(String orgId, Query q) {
        Instant now = Instant.now(clock);
        String key = orgId + "|" + q;
        Cached cached = cache.get(key);
        if (cached != null && cached.until().isAfter(now)) {
            return cached.status();
        }
        try {
            String token = tokens.token();
            StatusJson body = seller.get().uri(b -> b.path("/v1/orgs/{org}/selling-status")
                    .queryParamIfPresent("categoryCode", present(q.categoryCodes()))
                    .queryParamIfPresent("brand", present(q.brand()))
                    .queryParamIfPresent("productTypeCode", present(q.productTypeCode()))
                    .queryParamIfPresent("material", present(q.material()))
                    .queryParamIfPresent("gtin", present(q.gtin())).build(orgId)).headers(h -> {
                if (token != null) {
                    h.setBearerAuth(token);
                }
            }).retrieve().body(StatusJson.class);
            if (body == null || body.level() == null) {
                throw new SellingUnavailable("seller-service returned no selling status for " + orgId, null);
            }
            Status status = new Status(body.level(), body.reason(), body.missing() == null ? List.of()
                    : body.missing().stream().map(m -> new Missing(m.scope() == null ? null : m.scope().type(),
                    m.scope() == null ? null : m.scope().key(), m.label(), m.level(), m.state(), m.message(),
                    m.canApply())).toList(), body.pendingFacts());
            cache.put(key, new Cached(status, now.plus(ttl)));
            return status;
        } catch (SellingUnavailable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SellingUnavailable("seller-service could not say whether " + orgId + " may sell this: "
                    + e.getMessage(), e);
        }
    }

    private static Optional<String> present(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
