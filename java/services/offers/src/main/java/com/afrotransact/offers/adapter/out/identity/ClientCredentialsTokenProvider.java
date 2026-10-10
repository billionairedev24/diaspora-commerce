package com.afrotransact.offers.adapter.out.identity;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.locks.ReentrantLock;

import com.afrotransact.offers.port.CatalogClient.CatalogUnavailableException;
import com.afrotransact.offers.port.ServiceTokenProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * OAuth 2.0 client-credentials token of {@code offers-service} (scope {@code catalog:read}) from identity's
 * {@code /oauth2/token}, cached until one minute before it expires (one refresh at a time).
 */
@Component
class ClientCredentialsTokenProvider implements ServiceTokenProvider {

    private static final Duration SKEW = Duration.ofSeconds(60);

    private final RestClient identity;
    private final String tokenPath;
    private final String basic;
    private final String scopes;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile String token;
    private volatile Instant expiresAt = Instant.EPOCH;

    ClientCredentialsTokenProvider(RestClient.Builder builder,
                                   @Value("${at.offers.oauth.issuer-url}") String issuerUrl,
                                   @Value("${at.offers.oauth.token-path:/oauth2/token}") String tokenPath,
                                   @Value("${at.offers.oauth.client-id}") String clientId,
                                   @Value("${at.offers.oauth.client-secret}") String clientSecret,
                                   @Value("${at.offers.oauth.scopes}") String scopes,
                                   @Value("${at.offers.catalog.timeout:3s}") Duration timeout, Clock clock) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.identity = builder.clone().baseUrl(issuerUrl).requestFactory(factory).build();
        this.tokenPath = tokenPath;
        this.basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret)
                .getBytes(StandardCharsets.UTF_8));
        this.scopes = scopes;
        this.clock = clock;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenJson(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") Long expiresIn) {
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
            TokenJson body;
            try {
                body = identity.post().uri(tokenPath).header("Authorization", "Basic " + basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                        .retrieve().body(TokenJson.class);
            } catch (RestClientException ex) {
                throw new CatalogUnavailableException("identity token endpoint unavailable: " + ex.getMessage(), ex);
            }
            if (body == null || body.accessToken() == null || body.accessToken().isBlank()) {
                throw new CatalogUnavailableException("identity returned no access token", null);
            }
            token = body.accessToken();
            expiresAt = Instant.now(clock).plusSeconds(body.expiresIn() == null ? 300 : body.expiresIn()).minus(SKEW);
            return token;
        } finally {
            lock.unlock();
        }
    }
}
