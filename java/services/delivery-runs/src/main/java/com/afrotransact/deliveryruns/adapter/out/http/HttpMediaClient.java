package com.afrotransact.deliveryruns.adapter.out.http;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.port.MediaGateway;
import com.afrotransact.deliveryruns.port.UpstreamUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** media-service {@code GET /v1/assets/{id}} with the {@code delivery-runs-service} token (scope media:read). */
public class HttpMediaClient implements MediaGateway {

    private static final String SERVICE = "media";

    private final RestClient media;
    private final ClientCredentialsTokens tokens;

    public HttpMediaClient(String baseUrl, Duration timeout, String tokenUri, String clientId, String clientSecret,
                           Clock clock) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.media = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = new ClientCredentialsTokens(tokenUri, clientId, clientSecret, "media:read", timeout, clock,
                (message, cause) -> new UpstreamUnavailableException(SERVICE, message, cause));
    }

    @Override
    public Optional<MediaAsset> asset(UUID id) {
        try {
            JsonNode a = media.get().uri("/v1/assets/{id}", id).header("Authorization", "Bearer " + tokens.token())
                    .retrieve().body(JsonNode.class);
            if (a == null) {
                return Optional.empty();
            }
            return Optional.of(new MediaAsset(id, text(a, "url"), a.path("width").asInt(0),
                    a.path("height").asInt(0), text(a, "status"), text(a, "contentType")));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return Optional.empty();
            }
            throw new UpstreamUnavailableException(SERVICE, "media-service refused the asset lookup: "
                    + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException(SERVICE, "media-service failed: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }
}
