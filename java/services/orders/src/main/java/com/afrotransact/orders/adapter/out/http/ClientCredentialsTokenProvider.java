package com.afrotransact.orders.adapter.out.http;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.locks.ReentrantLock;

import com.afrotransact.orders.port.ServiceTokenProvider;
import com.afrotransact.orders.port.UpstreamFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * OAuth 2.0 client-credentials token of {@code orders-service} from identity's {@code /oauth2/token}, cached until one
 * minute before it expires (one refresh at a time).
 */
@Component
class ClientCredentialsTokenProvider implements ServiceTokenProvider {

    private static final Duration SKEW = Duration.ofSeconds(60);

    private final Upstream identity;
    private final String tokenPath;
    private final String clientId;
    private final String clientSecret;
    private final String scopes;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile String token;
    private volatile Instant expiresAt = Instant.EPOCH;

    ClientCredentialsTokenProvider(RestClient.Builder builder,
                                   @Value("${at.orders.oauth.issuer-url}") String issuerUrl,
                                   @Value("${at.orders.oauth.token-path:/oauth2/token}") String tokenPath,
                                   @Value("${at.orders.oauth.client-id}") String clientId,
                                   @Value("${at.orders.oauth.client-secret}") String clientSecret,
                                   @Value("${at.orders.oauth.scopes}") String scopes,
                                   @Value("${at.orders.upstreams.timeout:5s}") Duration timeout, Clock clock) {
        this.identity = new Upstream("identity", builder, issuerUrl, timeout);
        this.tokenPath = tokenPath;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scopes = scopes;
        this.clock = clock;
    }

    @Override
    public String token() {
        String current = token;
        if (current != null && Instant.now(clock).isBefore(expiresAt)) {
            return current;
        }
        lock.lock();
        try {
            if (token != null && Instant.now(clock).isBefore(expiresAt)) {
                return token;
            }
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("scope", scopes);
            String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret)
                    .getBytes(StandardCharsets.UTF_8));
            JsonNode body = identity.call(c -> c.post().uri(tokenPath)
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(JsonNode.class));
            if (body == null || body.path("access_token").asString("").isEmpty()) {
                throw new UpstreamFailure("identity", 0, "identity.no-token", "identity returned no access token",
                        true, null, null);
            }
            long ttl = body.path("expires_in").asLong(300);
            token = body.path("access_token").asString();
            expiresAt = Instant.now(clock).plusSeconds(ttl).minus(SKEW);
            return token;
        } finally {
            lock.unlock();
        }
    }
}
