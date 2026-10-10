package com.afrotransact.trustops.adapter.out.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * OAuth2 client-credentials access token of the {@code trust-ops-service} client (identity-service), cached until
 * shortly before it expires. A blank secret disables tokens (local profile, where the other services run without
 * security).
 */
final class ClientCredentialsTokens {

    private static final Duration EARLY_REFRESH = Duration.ofSeconds(30);

    private final RestClient client;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final String scopes;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    ClientCredentialsTokens(RestClient client, String tokenUri, String clientId, String clientSecret, String scopes,
                            Clock clock) {
        this.client = client;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scopes = scopes;
        this.clock = clock;
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
                if (scopes != null && !scopes.isBlank()) {
                    form.add("scope", scopes);
                }
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
