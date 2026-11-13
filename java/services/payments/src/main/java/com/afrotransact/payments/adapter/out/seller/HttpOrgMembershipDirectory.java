package com.afrotransact.payments.adapter.out.seller;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.payments.port.OrgMembershipDirectory;
import com.afrotransact.payments.port.OrgMembershipUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * {@link OrgMembershipDirectory} over the seller service's {@code GET /v1/orgs/{orgId}/members/{userId}/role}, read
 * with the {@code payments-service} client's own {@code sellers:membership.read} scope. Answers (including "not a
 * member") are remembered for {@code at.payments.membership-check.cache-ttl} (default 3s, hard cap 10s) so a burst of
 * calls is one lookup, never a meaningful window. Anything that is not a clear answer (no URL or secret configured,
 * timeout, 4xx, 5xx) throws {@link OrgMembershipUnavailableException}: the action is refused, not allowed.
 */
@Component
@ConditionalOnProperty(prefix = "at.payments.membership-check", name = "mode", havingValue = "live",
        matchIfMissing = true)
class HttpOrgMembershipDirectory implements OrgMembershipDirectory {

    static final String SCOPE = "sellers:membership.read";
    private static final Duration MAX_TTL = Duration.ofSeconds(10);
    private static final int MAX_ENTRIES = 5_000;

    private record Answer(Optional<String> role, Instant until) {
    }

    private final RestClient client;
    private final SellerServiceTokens tokens;
    private final boolean configured;
    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentHashMap<String, Answer> cache = new ConcurrentHashMap<>();

    HttpOrgMembershipDirectory(RestClient.Builder builder, Clock clock,
                               @Value("${at.payments.seller.base-url:}") String baseUrl,
                               @Value("${at.payments.seller.token-uri:}") String tokenUri,
                               @Value("${at.payments.seller.client-id:payments-service}") String clientId,
                               @Value("${at.payments.seller.client-secret:}") String clientSecret,
                               @Value("${at.payments.seller.timeout:3s}") Duration timeout,
                               @Value("${at.payments.membership-check.cache-ttl:3s}") Duration cacheTtl) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        RestClient base = builder.clone().requestFactory(factory).build();
        this.tokens = new SellerServiceTokens(base, tokenUri, clientId, clientSecret, SCOPE, clock);
        this.configured = baseUrl != null && !baseUrl.isBlank() && tokens.enabled();
        this.client = configured ? base.mutate().baseUrl(baseUrl).build() : base;
        this.ttl = cacheTtl.isNegative() ? Duration.ZERO : cacheTtl.compareTo(MAX_TTL) > 0 ? MAX_TTL : cacheTtl;
        this.clock = clock;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Optional<String> roleOf(String orgId, String userId) {
        if (!configured) {
            throw new OrgMembershipUnavailableException("Seller membership check is not configured (SELLER_URL, "
                    + "PAYMENTS_CLIENT_SECRET, AUTH_TOKEN_URL)", null);
        }
        String key = orgId + '\u0000' + userId;
        Instant now = clock.instant();
        Answer cached = cache.get(key);
        if (cached != null && now.isBefore(cached.until())) {
            return cached.role();
        }
        try {
            JsonNode n = client.get().uri("/v1/orgs/{orgId}/members/{userId}/role", orgId, userId)
                    .headers(h -> h.setBearerAuth(tokens.token()))
                    .retrieve().body(JsonNode.class);
            if (n == null || !n.has("member")) {
                throw new OrgMembershipUnavailableException("Seller service gave no membership answer", null);
            }
            Optional<String> role = n.path("member").asBoolean(false)
                    ? Optional.ofNullable(n.path("roleKey").asString(null)).filter(s -> !s.isBlank())
                    : Optional.empty();
            if (ttl.isPositive()) {
                if (cache.size() >= MAX_ENTRIES) {
                    cache.clear();
                }
                cache.put(key, new Answer(role, now.plus(ttl)));
            }
            return role;
        } catch (RestClientException | IllegalStateException e) {
            cache.remove(key);
            throw new OrgMembershipUnavailableException("Seller service could not confirm the membership ("
                    + e.getMessage() + ")", e);
        }
    }
}
