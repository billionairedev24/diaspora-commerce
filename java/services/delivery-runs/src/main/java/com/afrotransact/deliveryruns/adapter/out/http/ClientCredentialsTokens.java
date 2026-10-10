package com.afrotransact.deliveryruns.adapter.out.http;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * OAuth 2.0 client-credentials token of the {@code delivery-runs-service} client from identity's token endpoint,
 * cached until one minute before it expires (one refresh at a time). Shared by the geo, media and orders adapters;
 * each passes the exception its port declares for an unavailable upstream.
 */
public class ClientCredentialsTokens {

    private static final Duration SKEW = Duration.ofSeconds(60);

    private final RestClient identity;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final String scopes;
    private final Clock clock;
    private final BiFunction<String, Throwable, RuntimeException> failure;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile String token;
    private volatile Instant expiresAt = Instant.EPOCH;

    /**
     * @param scopes  space-separated scopes to request
     * @param failure builds the exception thrown when no token can be obtained (message, cause)
     */
    public ClientCredentialsTokens(String tokenUri, String clientId, String clientSecret, String scopes,
                                   Duration timeout, Clock clock,
                                   BiFunction<String, Throwable, RuntimeException> failure) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.identity = RestClient.builder().requestFactory(factory).build();
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scopes = scopes;
        this.clock = clock;
        this.failure = failure;
    }

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
            if (clientSecret == null || clientSecret.isBlank()) {
                throw failure.apply("No client secret for " + clientId + " (DELIVERY_RUNS_CLIENT_SECRET)", null);
            }
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("scope", scopes);
            String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret)
                    .getBytes(StandardCharsets.UTF_8));
            JsonNode body = identity.post().uri(tokenUri)
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve().body(JsonNode.class);
            if (body == null || body.path("access_token").asString("").isEmpty()) {
                throw failure.apply("identity returned no access token", null);
            }
            token = body.path("access_token").asString();
            expiresAt = Instant.now(clock).plusSeconds(body.path("expires_in").asLong(300)).minus(SKEW);
            return token;
        } catch (RestClientException e) {
            throw failure.apply("Token request failed: " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }
}
