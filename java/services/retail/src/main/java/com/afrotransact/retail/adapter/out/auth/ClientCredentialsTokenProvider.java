package com.afrotransact.retail.adapter.out.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import com.afrotransact.retail.port.ServiceTokenProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OAuth 2.1 client-credentials grant against identity-service ({@code POST /oauth2/token}, client_secret_basic).
 * The token is cached until one minute before it expires. Without a client secret (local profile) no token is sent.
 */
public class ClientCredentialsTokenProvider implements ServiceTokenProvider {

    private static final Duration EARLY_REFRESH = Duration.ofSeconds(60);

    private final RestClient http;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final String scopes;
    private final JsonMapper json;
    private final Clock clock;

    private String cached;
    private Instant refreshAt = Instant.EPOCH;

    public ClientCredentialsTokenProvider(RestClient http, String tokenUri, String clientId, String clientSecret,
                                          String scopes, JsonMapper json, Clock clock) {
        this.http = http;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scopes = scopes;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public synchronized Optional<String> token() {
        if (clientSecret == null || clientSecret.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        if (cached != null && now.isBefore(refreshAt)) {
            return Optional.of(cached);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        if (scopes != null && !scopes.isBlank()) {
            form.add("scope", scopes);
        }
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret)
                .getBytes(StandardCharsets.UTF_8));
        String body = http.post().uri(tokenUri).header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class);
        JsonNode node = json.readTree(body == null ? "{}" : body);
        String token = node.path("access_token").asString(null);
        if (token == null) {
            throw new IllegalStateException("Token endpoint returned no access_token");
        }
        long expiresIn = node.path("expires_in").asLong(300);
        cached = token;
        refreshAt = now.plusSeconds(expiresIn).minus(EARLY_REFRESH);
        return Optional.of(cached);
    }
}
