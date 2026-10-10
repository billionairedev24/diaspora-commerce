package com.afrotransact.taxreturns.adapter.out.http;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * OAuth2 client-credentials token of the {@code tax-returns-service} client (identity-service), cached until shortly
 * before it expires. A blank secret disables tokens (local profile, where upstreams run without security).
 */
@Component
class ClientCredentialsTokens {

    /** Scopes requested: refunds at payments, return postings at inventory, return labels at carrier-gateway. */
    static final String SCOPES = "payments:service inventory:adjust shipping:label";
    private static final Duration EARLY_REFRESH = Duration.ofSeconds(60);

    private final RestClient client;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    ClientCredentialsTokens(RestClient.Builder builder, TaxReturnsProperties props, Clock clock) {
        TaxReturnsProperties.Upstreams u = props.upstreams();
        this.client = builder.clone().requestFactory(factory(u.timeout())).build();
        this.tokenUri = u.tokenUri();
        this.clientId = u.clientId();
        this.clientSecret = u.clientSecret();
        this.clock = clock;
    }

    static JdkClientHttpRequestFactory factory(Duration timeout) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        return factory;
    }

    boolean enabled() {
        return clientSecret != null && !clientSecret.isBlank();
    }

    /** A valid bearer token, fetching a new one when needed. */
    String token() {
        lock.lock();
        try {
            Instant now = clock.instant();
            if (token == null || now.isAfter(expiresAt.minus(EARLY_REFRESH))) {
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("grant_type", "client_credentials");
                form.add("scope", SCOPES);
                JsonNode body = client.post().uri(tokenUri)
                        .headers(h -> h.setBasicAuth(clientId, clientSecret))
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(JsonNode.class);
                if (body == null || body.path("access_token").asString("").isEmpty()) {
                    throw new IllegalStateException("Token endpoint returned no access_token");
                }
                token = body.path("access_token").asString();
                expiresAt = now.plusSeconds(body.path("expires_in").asLong(300));
            }
            return token;
        } finally {
            lock.unlock();
        }
    }
}
