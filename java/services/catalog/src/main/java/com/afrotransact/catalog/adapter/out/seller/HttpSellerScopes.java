package com.afrotransact.catalog.adapter.out.seller;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.catalog.port.SellerScopes;
import com.afrotransact.catalog.port.ServiceTokens;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * seller-service {@code GET /v1/orgs/{orgId}/catalog-scope} with catalog's service token, cached briefly per
 * organisation (a department request approved a moment ago shows within the TTL). A failure is never cached and never
 * guessed: it is {@link ScopeUnavailable}, so the catalog refuses (fail closed).
 */
@Component
class HttpSellerScopes implements SellerScopes {

    private final RestClient seller;
    private final ServiceTokens tokens;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    HttpSellerScopes(RestClient.Builder builder, ServiceTokens tokens,
                     @Value("${at.catalog.seller.base-url:http://localhost:8106}") String baseUrl,
                     @Value("${at.catalog.seller.timeout:3s}") Duration timeout,
                     @Value("${at.catalog.seller.scope-ttl:15s}") Duration ttl, Clock clock) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.seller = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = tokens;
        this.ttl = ttl;
        this.clock = clock;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RequestJson(String departmentCode, String status, List<String> needs, Instant createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ScopeJson(boolean exempt, List<String> departments, List<RequestJson> requests) {
    }

    private record Cached(OrgScope scope, Instant until) {
    }

    @Override
    public OrgScope of(String orgId) {
        Instant now = Instant.now(clock);
        Cached cached = cache.get(orgId);
        if (cached != null && cached.until().isAfter(now)) {
            return cached.scope();
        }
        try {
            String token = tokens.token();
            ScopeJson body = seller.get().uri("/v1/orgs/{org}/catalog-scope", orgId).headers(h -> {
                if (token != null) {
                    h.setBearerAuth(token);
                }
            }).retrieve().body(ScopeJson.class);
            if (body == null) {
                throw new ScopeUnavailable("seller-service returned no scope for " + orgId, null);
            }
            OrgScope scope = new OrgScope(body.exempt(),
                    body.departments() == null ? Set.of() : Set.copyOf(body.departments()),
                    body.requests() == null ? List.of() : body.requests().stream().map(r -> new DepartmentRequest(
                            r.departmentCode(), r.status(), r.needs() == null ? List.of() : r.needs(), r.createdAt()))
                            .toList());
            cache.put(orgId, new Cached(scope, now.plus(ttl)));
            return scope;
        } catch (ScopeUnavailable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ScopeUnavailable("seller-service could not say which departments " + orgId + " sells in: "
                    + e.getMessage(), e);
        }
    }
}
